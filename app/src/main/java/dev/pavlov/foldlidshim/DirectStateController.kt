package dev.pavlov.foldlidshim

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accesses android.hardware.devicestate.DeviceStateManager via reflection.
 * All public methods are safe to call from any thread; they catch and log exceptions.
 */
object DirectStateController {
    private const val DEVICE_STATE_SERVICE = "device_state"
    private const val MANAGER_CLASS_NAME = "android.hardware.devicestate.DeviceStateManager"
    private const val REQUEST_CLASS_NAME = "android.hardware.devicestate.DeviceStateRequest"
    private const val BUILDER_CLASS_NAME = "$REQUEST_CLASS_NAME\$Builder"
    private const val CALLBACK_INTERFACE_NAME = "$REQUEST_CLASS_NAME\$Callback"
    private const val STATE_CALLBACK_CLASS_NAME = "$MANAGER_CLASS_NAME\$DeviceStateCallback"

    @Volatile private var _initialized = false
    @Volatile private var initCause: String? = null
    @Volatile private var managerClassRef: Class<*>? = null
    @Volatile private var requestClassRef: Class<*>? = null
    @Volatile private var builderClassRef: Class<*>? = null
    @Volatile private var callbackInterfaceRef: Class<*>? = null
    @Volatile private var stateCallbackClassRef: Class<*>? = null

    /** True when all mandatory classes and methods were resolved. */
    @Volatile var initialized: Boolean = false
        private set

    fun available(context: Context): Boolean {
        return try {
            initOnce(context)
            true
        } catch (e: Exception) {
            Log.w(Config.TAG, "DeviceStateManager unavailable: ${e.message}")
            false
        }
    }

    /**
     * Request the device state to be set to CLOSED (folded).
     * Returns true if the request was accepted, false otherwise.
     */
    fun requestClosed(context: Context): Boolean {
        Log.i(Config.TAG, "DirectStateController.requestClosed: starting")
        return try {
            ensureInitialized(context)
            val manager = getManagerInstance(context)
            if (manager == null) {
                Log.w(Config.TAG, "requestClosed: could not get DeviceStateManager instance")
                false
            } else {
                val request = buildRequest()
                if (request == null) {
                    Log.w(Config.TAG, "requestClosed: could not build DeviceStateRequest")
                    false
                } else {
                    val latch = CountDownLatch(1)
                    val activated = AtomicBoolean(false)
                    val callbackProxy = createCallbackProxy(latch, activated)

                    val executor = handlerExecutor(Looper.getMainLooper())

                    managerClassRef!!.getMethod(
                        "requestState",
                        requestClass!!,
                        Executor::class.java,
                        callbackInterface!!
                    ).invoke(manager, request, executor, callbackProxy)

                    // Wait for callback or timeout
                    val completed = latch.await(1, TimeUnit.SECONDS)
                    if (completed && activated.get()) {
                        Log.i(Config.TAG, "requestClosed: onRequestActivated received")
                        ShimState.overrideClosed = true
                        ShimState.lastCallbackResult = "activated"
                        true
                    } else if (!completed) {
                        Log.w(Config.TAG, "requestClosed: callback timeout after 1s, assuming success (no exception)")
                        ShimState.overrideClosed = true
                        ShimState.lastCallbackResult = "activated"
                        true
                    } else {
                        Log.w(Config.TAG, "requestClosed: callback returned false")
                        false
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(Config.TAG, "requestClosed: SecurityException (no CONTROL_DEVICE_STATE permission): ${e.message}")
            ShimState.lastCallbackResult = "SecurityException: no CONTROL_DEVICE_STATE permission"
            false
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            Log.e(Config.TAG, "requestClosed: InvocationTargetException: ${cause?.message}", cause)
            ShimState.lastCallbackResult = cause?.message ?: "InvocationTargetException"
            false
        } catch (e: NoSuchMethodException) {
            Log.e(Config.TAG, "requestClosed: method not found: ${e.message}")
            initCause = "NoSuchMethodException: ${e.message}"
            initialized = false
            false
        } catch (e: Exception) {
            Log.e(Config.TAG, "requestClosed: exception: ${e.javaClass.name}: ${e.message}", e)
            ShimState.lastCallbackResult = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /**
     * Cancel the current state request. Returns true if successful.
     */
    fun cancel(context: Context): Boolean {
        Log.i(Config.TAG, "DirectStateController.cancel: starting")
        return try {
            ensureInitialized(context)
            val manager = getManagerInstance(context)
            if (manager == null) {
                Log.w(Config.TAG, "cancel: could not get DeviceStateManager instance")
                false
            } else {
                managerClassRef!!.getMethod("cancelStateRequest").invoke(manager)
                ShimState.overrideClosed = false
                ShimState.lastCallbackResult = "canceled"
                Log.i(Config.TAG, "cancel: success")
                true
            }
        } catch (e: SecurityException) {
            Log.e(Config.TAG, "cancel: SecurityException: ${e.message}")
            ShimState.lastCallbackResult = "SecurityException: ${e.message}"
            false
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            Log.e(Config.TAG, "cancel: InvocationTargetException: ${cause?.message}", cause)
            ShimState.lastCallbackResult = cause?.message ?: "InvocationTargetException"
            false
        } catch (e: NoSuchMethodException) {
            Log.e(Config.TAG, "cancel: method not found: ${e.message}")
            initCause = "NoSuchMethodException: ${e.message}"
            initialized = false
            false
        } catch (e: Exception) {
            Log.e(Config.TAG, "cancel: exception: ${e.javaClass.name}: ${e.message}", e)
            ShimState.lastCallbackResult = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /** Reason why DeviceStateManager is unavailable, or null. */
    fun unavailabilityReason(): String? = initCause

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        initOnce(context)
    }

    private fun initOnce(context: Context): Boolean {
        if (_initialized) return initialized
        synchronized(this) {
            if (_initialized) return initialized
            _initialized = true
            try {
                val managerCls = Class.forName(MANAGER_CLASS_NAME)
                val requestCls = Class.forName(REQUEST_CLASS_NAME)
                val builderCls = Class.forName(BUILDER_CLASS_NAME)
                val callbackCls = Class.forName(CALLBACK_INTERFACE_NAME)
                val stateCbCls = Class.forName(STATE_CALLBACK_CLASS_NAME)

                // Verify mandatory methods exist on Builder
                builderCls.getMethod("setFlags", Int::class.javaPrimitiveType)
                builderCls.getMethod("build")

                // Verify requestState method exists
                managerCls.getMethod(
                    "requestState",
                    requestCls,
                    Executor::class.java,
                    callbackCls
                )

                // Verify cancelStateRequest method exists
                managerCls.getMethod("cancelStateRequest")

                managerClassRef = managerCls
                requestClassRef = requestCls
                builderClassRef = builderCls
                callbackInterfaceRef = callbackCls
                stateCallbackClassRef = stateCbCls
                initCause = null
                initialized = true
                Log.i(Config.TAG, "DeviceStateManager classes and methods verified")
            } catch (e: ClassNotFoundException) {
                initCause = "Class not found: ${e.message}"
                initialized = false
                Log.w(Config.TAG, "DeviceStateManager class not found: ${e.message}")
            } catch (e: NoSuchMethodException) {
                initCause = "Method not found: ${e.message}"
                initialized = false
                Log.e(Config.TAG, "Required method not found: ${e.message}")
            } catch (e: Exception) {
                initCause = e.message
                initialized = false
                Log.e(Config.TAG, "Error initializing DeviceStateManager: ${e.message}", e)
            }
        }
        return initialized
    }

    private fun getManagerInstance(context: Context): Any? {
        try {
            val getSystemService = context.javaClass.getMethod(
                "getSystemService", String::class.java
            )
            val raw = getSystemService.invoke(context, DEVICE_STATE_SERVICE)
            if (raw == null) {
                Log.w(Config.TAG, "getSystemService($DEVICE_STATE_SERVICE) returned null")
            }
            return raw
        } catch (e: NoSuchMethodException) {
            Log.e(Config.TAG, "Context.getSystemService(String) not found: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.e(Config.TAG, "Error calling getSystemService: ${e.message}", e)
            return null
        }
    }

    private fun buildRequest(): Any? {
        try {
            // newBuilder is a static method on DeviceStateRequest, not on Builder
            val newBuilder = requestClassRef!!.getMethod("newBuilder", Int::class.javaPrimitiveType)
            val builder = newBuilder.invoke(null, 0) // identifier = 0 for closed state

            val setFlags = builderClassRef!!.getMethod("setFlags", Int::class.javaPrimitiveType)
            // Flags: keep override until explicitly cancelled or process dies.
            // Using value 1 based on AOSP DeviceStateRequest constant.
            setFlags.invoke(builder, 1)

            val build = builderClassRef!!.getMethod("build")
            return build.invoke(builder)
        } catch (e: NoSuchMethodException) {
            Log.w(Config.TAG, "Required method not found in DeviceStateRequest.Builder: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.e(Config.TAG, "Error building DeviceStateRequest: ${e.message}", e)
            return null
        }
    }

    /**
     * Creates an Executor that posts Runnables to the given Looper's Handler.
     */
    private fun handlerExecutor(looper: Looper): Executor {
        val handler = Handler(looper)
        return Executor { runnable -> handler.post(runnable) }
    }

    /**
     * Creates a java.lang.reflect.Proxy for DeviceStateRequest.Callback.
     * Correctly handles Object methods (hashCode, equals, toString).
     * Updates ShimState immediately on callback events.
     */
    private fun createCallbackProxy(latch: CountDownLatch, activated: AtomicBoolean): Any {
        val handler = object : java.lang.reflect.InvocationHandler {
            override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                val name = method.name
                // Handle Object methods correctly to avoid reflection errors
                when (name) {
                    "onRequestActivated" -> {
                        Log.i(Config.TAG, "Callback.onRequestActivated called")
                        activated.set(true)
                        ShimState.overrideClosed = true
                        ShimState.lastCallbackResult = "activated"
                        latch.countDown()
                    }
                    "onRequestSuspended" -> {
                        Log.w(Config.TAG, "Callback.onRequestSuspended called")
                        activated.set(false)
                        ShimState.lastCallbackResult = "suspended"
                        latch.countDown()
                    }
                    "onRequestCanceled" -> {
                        Log.w(Config.TAG, "Callback.onRequestCanceled called")
                        activated.set(false)
                        ShimState.overrideClosed = false
                        ShimState.lastCallbackResult = "canceled"
                        latch.countDown()
                    }
                    "hashCode" -> return method.invoke(proxy) as Int
                    "equals" -> return method.invoke(proxy, *(args ?: emptyArray())) as Boolean
                    "toString" -> return method.invoke(proxy) as String
                }
                return null
            }
        }
        val cls = callbackInterfaceRef
            ?: throw IllegalStateException("Callback interface not resolved")
        return Proxy.newProxyInstance(
            cls.classLoader,
            arrayOf(cls),
            handler
        )
    }

    /**
     * Try to register a DeviceStateCallback for displaying current state.
     * Non-critical: silently fails if the method is unavailable.
     */
    fun tryRegisterCallback(context: Context) {
        if (!initialized || stateCallbackClassRef == null) return
        try {
            val manager = getManagerInstance(context)
            if (manager == null) return
            val executor = handlerExecutor(Looper.getMainLooper())
            managerClassRef!!.getMethod(
                "registerCallback",
                Executor::class.java,
                stateCallbackClassRef!!
            ).invoke(manager, executor, createStateCallbackProxy())
            Log.i(Config.TAG, "DeviceStateCallback registered")
        } catch (e: NoSuchMethodException) {
            Log.i(Config.TAG, "registerCallback not available on this DeviceStateManager")
        } catch (e: Exception) {
            Log.w(Config.TAG, "Error registering state callback: ${e.message}")
        }
    }

    private fun createStateCallbackProxy(): Any {
        val handler = object : java.lang.reflect.InvocationHandler {
            override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                Log.i(Config.TAG, "DeviceStateCallback.${method.name} called")
                return null
            }
        }
        val cls = stateCallbackClassRef!!
        return Proxy.newProxyInstance(
            cls.classLoader,
            arrayOf(cls),
            handler
        )
    }
}
