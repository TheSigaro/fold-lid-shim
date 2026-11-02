package dev.pavlov.foldlidshim

import android.content.Context
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Accesses android.hardware.devicestate.DeviceStateManager via reflection.
 * All public methods are safe to call from any thread; they catch and log exceptions.
 */
object DirectStateController {
    // One executor for the process, including requests made by the UI test.
    private val callbackExecutor = Executor { main.post(it) }
    @Volatile var manualOpenGeneration = 0L
        private set
    fun manualOpen() { manualOpenGeneration++ }
    @Volatile var closedSentAt: Long? = null
        private set
    @Volatile var onCanceled: ((Long, Long, Boolean) -> Unit)? = null
    private var generation = 0L
    private var requestSequence = 0L
    private val main = Handler(Looper.getMainLooper())
    @Volatile var requestedState: Int? = null
        private set
    private var applicationPower: PowerManager? = null
    private var stateCallbackRegistered = false
    private var stateCallback: Any? = null
    val hasClosedRequest: Boolean get() = hasRequest && requestedState == 0

    enum class Desired { CLOSED, OPENED, RELEASE }

    @Synchronized
    fun applyDesired(context: Context, desired: Desired, relock: Boolean = false): Boolean = when (desired) {
        Desired.RELEASE -> cancel(context)
        Desired.CLOSED -> if (hasRequest ||
            (ShimState.directBaseState ?: ShimState.directUnrequestedState) == 0) true
            else requestState(context, 0, relock)
        Desired.OPENED -> if (hasRequest) true else requestState(context, 3, false)
    }

    // Samsung firmware bit 2 preserves the override during sleep. Bit 1 is forbidden.
    private const val REQUEST_FLAGS = 2
    private var relockGeneration: Long? = null

    @Volatile var hasRequest = false
        private set

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
        } catch (e: Exception) {
            Log.w(Config.TAG, "DeviceStateManager unavailable: ${e.message}")
            false
        }
    }

    /**
     * Request CLOSED (0) or OPENED (3) while the requester is the top app.
     * Returns true if the request was accepted, false otherwise.
     */
    @Synchronized
    private fun requestState(context: Context, state: Int, relock: Boolean): Boolean {
        ShimState.lastCallbackResult = "request failed"
        Timeline.event("requestState state=$state")
        return try {
            ensureInitialized(context)
            val manager = getManagerInstance(context)
            if (manager == null) {
                error("DeviceStateManager instance unavailable")
            } else {
                val request = buildRequest(state)
                val previousGeneration = generation
                val previousState = requestedState
                val previousHasRequest = hasRequest
                val previousPending = ShimState.directConfirmationPending
                val previousUnrequestedState = ShimState.directUnrequestedState
                val requestGeneration = ++requestSequence
                generation = requestGeneration
                val callbackProxy = createCallbackProxy(requestGeneration, state)
                hasRequest = true
                ShimState.directUnrequestedState = null
                requestedState = state
                ShimState.directRequestGeneration = requestGeneration
                ShimState.directConfirmationPending = state == 0
                if (relock) relockGeneration = requestGeneration
                if (state == 0) main.postDelayed({
                    synchronized(this@DirectStateController) {
                        if (generation == requestGeneration) {
                            ShimState.directConfirmationPending = false
                            ShimState.changed()
                        }
                    }
                }, 5000L)
                ShimState.lastCallbackResult = "request accepted, waiting for activation"
                try {
                    if (state == 0) closedSentAt = SystemClock.elapsedRealtime()
                    Timeline.event("requestState send state=$state generation=$requestGeneration flags=$REQUEST_FLAGS")
                    managerClassRef!!.getMethod(
                        "requestState",
                        requestClassRef!!,
                        Executor::class.java,
                        callbackInterfaceRef!!
                    ).invoke(manager, request, callbackExecutor, callbackProxy)
                } catch (e: Exception) {
                    if (relockGeneration == requestGeneration) relockGeneration = null
                    ShimState.directConfirmationPending = previousPending
                    ShimState.directUnrequestedState = previousUnrequestedState
                    requestedState = previousState
                    hasRequest = previousHasRequest
                    generation = previousGeneration
                    ShimState.directRequestGeneration = previousGeneration
                    throw e
                }
                Log.i(Config.TAG, "requestState: state=$state accepted (callback pending)")
                ShimState.changed()
                true
            }
        } catch (e: Exception) {
            val cause = if (e is InvocationTargetException) e.cause ?: e else e
            val detail = "${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
            Log.e(Config.TAG, "requestState failed: $detail", cause)
            if (state == 3) {
                Timeline.event("OPENED refused: $detail")
                ShimState.lastCallbackResult = StateActivity.OPEN_UNAVAILABLE
            } else ShimState.lastCallbackResult = detail
            ShimState.changed()
            false
        }
    }

    /**
     * Cancel the current state request. Returns true if successful.
     */
    @Synchronized
    fun cancel(context: Context): Boolean {
        ShimState.lastCallbackResult = "cancel failed"
        Timeline.event("cancelStateRequest")
        return try {
            ensureInitialized(context)
            val manager = getManagerInstance(context)
            if (manager == null) {
                Log.w(Config.TAG, "cancel: could not get DeviceStateManager instance")
                false
            } else {
                Timeline.event("cancelStateRequest send generation=$generation")
                managerClassRef!!.getMethod("cancelStateRequest").invoke(manager)
                // Keep ownership until onRequestCanceled confirms removal.
                // A recovered sticky request has no callback in this process.
                ShimState.directConfirmationPending = false
                ShimState.lastCallbackResult = if (hasRequest)
                    "cancel accepted, waiting for callback" else "cancel accepted"
                ShimState.changed()
                Timeline.event("cancel accepted awaitingCallback=$hasRequest")
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
        check(initOnce(context)) { initCause ?: "DeviceStateManager unavailable" }
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

    private fun buildRequest(state: Int): Any {
        // newBuilder is a static method on DeviceStateRequest, not on Builder.
        val newBuilder = requestClassRef!!.getMethod("newBuilder", Int::class.javaPrimitiveType)
        val builder = newBuilder.invoke(null, state)
        val setFlags = builderClassRef!!.getMethod("setFlags", Int::class.javaPrimitiveType)
        setFlags.invoke(builder, REQUEST_FLAGS)
        return builderClassRef!!.getMethod("build").invoke(builder)
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
    private fun createCallbackProxy(requestGeneration: Long, state: Int): Any {
        val handler = object : java.lang.reflect.InvocationHandler {
            override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                when (method.name) {
                    "hashCode" -> return System.identityHashCode(proxy)
                    "equals" -> return proxy === args?.getOrNull(0)
                    "toString" -> return "${proxy.javaClass.name}@${System.identityHashCode(proxy)}"
                }
                synchronized(this@DirectStateController) {
                    if (requestGeneration != generation) return null
                    when (method.name) {
                        "onRequestActivated" -> {
                            ShimState.overrideClosed = state == 0
                            ShimState.lastCallbackResult = "activated"
                            if (relockGeneration == requestGeneration) {
                                relockGeneration = null
                                Timeline.event(if (FoldConfirmAccessibilityService.lockScreen())
                                    "relock after closed" else "lock unavailable")
                            }
                        }
                        "onRequestSuspended" -> {
                            ShimState.overrideClosed = false
                            ShimState.lastCallbackResult = "suspended"
                        }
                        "onRequestCanceled" -> {
                            onCanceled?.invoke(requestGeneration, SystemClock.elapsedRealtime(),
                                applicationPower?.isInteractive == true)
                            relockGeneration = null
                            requestedState = null
                            hasRequest = false
                            ShimState.overrideClosed = false
                            ShimState.lastCallbackResult = "canceled"
                        }
                        else -> return null
                    }
                    ShimState.directConfirmationPending = false
                    Timeline.event("request callback ${method.name} generation=$requestGeneration")
                    ShimState.changed()
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
    @Synchronized
    fun tryRegisterCallback(context: Context) {
        applicationPower = context.getSystemService(PowerManager::class.java)
        if (stateCallbackRegistered || !available(context) || stateCallbackClassRef == null) return
        try {
            val manager = getManagerInstance(context)
            if (manager == null) return
            val executor = handlerExecutor(Looper.getMainLooper())
            val callback = createStateCallbackProxy()
            managerClassRef!!.getMethod(
                "registerCallback",
                Executor::class.java,
                stateCallbackClassRef!!
            ).invoke(manager, executor, callback)
            stateCallback = callback
            stateCallbackRegistered = true
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
                when (method.name) {
                    "hashCode" -> return System.identityHashCode(proxy)
                    "equals" -> return proxy === args?.getOrNull(0)
                    "toString" -> return proxy.javaClass.name
                }
                if (method.name in listOf("onDeviceStateChanged", "onStateChanged", "onBaseStateChanged")) {
                    val state = args?.getOrNull(0)
                    val identifier = when (state) {
                        is Int -> state
                        null -> null
                        else -> state.javaClass.getMethod("getIdentifier").invoke(state) as Int
                    }
                    synchronized(this@DirectStateController) {
                        if (method.name == "onBaseStateChanged") ShimState.directBaseState = identifier
                        else {
                            ShimState.directCurrentState = identifier
                            ShimState.directUnrequestedState = identifier.takeUnless { hasRequest }
                        }
                    }
                    Timeline.event(if (method.name == "onBaseStateChanged") "base state=$identifier" else "device state=$identifier")
                    ShimState.changed()
                }
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
