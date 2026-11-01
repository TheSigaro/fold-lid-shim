package dev.pavlov.foldlidshim

import android.content.Context
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Observe commits only. Never requests a state and never binds Shizuku. */
internal class RecordingStateSource(private val onState: (Int?) -> Unit) {
    private var manager: Any? = null
    private var callback: Any? = null
    private var callbackClass: Class<*>? = null
    var error: String? = null
        private set

    fun start(context: Context, executor: Executor) {
        ShimState.addStateListener(onState)
        // A known state proves the process-wide direct callback is already installed.
        if (ShimState.directCurrentState != null) {
            onState(ShimState.directCurrentState)
            return
        }
        try {
            val cls = Class.forName("android.hardware.devicestate.DeviceStateManager")
            val cb = Class.forName("android.hardware.devicestate.DeviceStateManager\$DeviceStateCallback")
            val proxy = Proxy.newProxyInstance(cb.classLoader, arrayOf(cb)) { instance, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args?.getOrNull(0)
                    "toString" -> "SensorRecordingStateCallback"
                    "onDeviceStateChanged", "onStateChanged" -> {
                        val state = args?.getOrNull(0)
                        val id = if (state is Int) state else
                            state?.javaClass?.getMethod("getIdentifier")?.invoke(state) as? Int
                        onState(id)
                        null
                    }
                    else -> null
                }
            }
            val service = context.getSystemService("device_state") ?: throw IllegalStateException("Нет device_state")
            cls.getMethod("registerCallback", Executor::class.java, cb).invoke(service, executor, proxy)
            manager = service
            callback = proxy
            callbackClass = cb
        } catch (e: Exception) {
            error = "Состояние недоступно: ${e.cause?.message ?: e.message}"
        }
    }

    fun stop() {
        ShimState.removeStateListener(onState)
        val cb = callback ?: return
        runCatching {
            manager!!.javaClass.getMethod("unregisterCallback", callbackClass!!).invoke(manager, cb)
        }
        callback = null
        manager = null
    }
}
