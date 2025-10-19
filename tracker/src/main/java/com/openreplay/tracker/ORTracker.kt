package com.openreplay.tracker

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.view.View
import android.view.ViewGroup
import com.openreplay.tracker.listeners.Crash
import com.openreplay.tracker.listeners.LifecycleManager
import com.openreplay.tracker.listeners.LogsListener
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.managers.NetworkManager
import com.openreplay.tracker.managers.ScreenshotManager
import com.openreplay.tracker.managers.UserDefaults
import com.openreplay.tracker.models.OROptions
import com.openreplay.tracker.models.RecordingQuality
import com.openreplay.tracker.models.SessionRequest
import com.openreplay.tracker.models.script.ORMobileEvent
import com.openreplay.tracker.models.script.ORMobileUserID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

private enum class RecorderState { IDLE, INITIALIZED, RECORDING, STOPPING }

@SuppressLint("StaticFieldLeak")
object OpenReplay {
    var projectKey: String? = null
        private set

    var options: OROptions = OROptions.defaults
        private set

    var serverURL: String
        get() = NetworkManager.baseUrl
        set(value) { NetworkManager.baseUrl = value }
    private var app: Application? = null
    private var appContext: Context? = null
    private var lifecycleManager: LifecycleManager? = null
    private var networkCallbackRegistered = false
    private var connectivityManager: ConnectivityManager? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = MainScope()

    private var state: RecorderState = RecorderState.IDLE
    private val isInitialized = AtomicBoolean(false)
    private val autoRecordingEnabled = AtomicBoolean(false)
    private var sessionStartTs: Long = 0L
    private var lateMessagesFile: File? = null
    @Volatile private var currentActivity: Activity? = null

    private val startedActivities = AtomicInteger(0)
    private var lastBecameBackgroundAt: Long = 0L

    fun initialize(context: Context, projectKey: String, options: OROptions = OROptions.defaults) {
        val application = (context.applicationContext as? Application)
            ?: throw IllegalArgumentException("OpenReplay.initialize requires Application context")

        if (isInitialized.getAndSet(true)) {
            throw IllegalArgumentException("OpenReplay has been already initialized")
        }

        this.app = application
        this.appContext = application.applicationContext
        this.projectKey = projectKey
        this.options = options

        NetworkManager.initialize(application)
        CoroutineScope(Dispatchers.IO).launch { UserDefaults.init(application) }

        lifecycleManager = LifecycleManager() { event, activity ->
            when (event) {
                LifecycleManager.Event.ActivityStarted -> {
                    startedActivities.incrementAndGet()
                    updateCurrentActivity(activity)
                    onAppForeground()
                }
                LifecycleManager.Event.ActivityResumed -> {
                    updateCurrentActivity(activity)
                }
                LifecycleManager.Event.ActivityPaused -> {
                }
                LifecycleManager.Event.ActivityStopped -> {
                    val left = startedActivities.decrementAndGet().coerceAtLeast(0)
                    if (left == 0) {
                        lastBecameBackgroundAt = System.currentTimeMillis()
                        onAppBackground()
                    }
                }
            }
        }.also {
            application.registerActivityLifecycleCallbacks(it)
        }
        registerNetworkCallbacks(application)

        checkForLateMessages()
        state = RecorderState.INITIALIZED
    }
    fun enableAutoRecording(enable: Boolean) {
        autoRecordingEnabled.set(enable)
        if (enable && startedActivities.get() > 0 && state == RecorderState.INITIALIZED) {
            startRecording()
        }
        if (!enable && state == RecorderState.RECORDING) {
        }
    }

    fun startRecording(optionsOverride: OROptions? = null, onStarted: (() -> Unit)? = null) {
        if (!isInitialized.get()) {
            DebugUtils.log("OpenReplay not initialized; call initialize() first")
            throw IllegalArgumentException("Open replay not initialized")
        }
        if (state == RecorderState.STOPPING) return

        optionsOverride?.let { this.options = this.options.merge(it) }

        state = RecorderState.RECORDING
        sessionStartTs = Date().time

        SessionRequest.create(appContext!!, doNotRecord = false) { sessionResponse ->
            if (sessionResponse == null) {
                DebugUtils.error("OpenReplay: no response from /start")
                state = RecorderState.INITIALIZED
                return@create
            }
            MessageCollector.start(appContext!!)
            if (options.screen) {
                ScreenshotManager.setSettings(getCaptureSettings( fps = options.fps, quality = options.screenshotQuality))
                ScreenshotManager.updateCurrentActivity(currentActivity)
                ScreenshotManager.start(appContext!!, sessionStartTs, options)
            }
            if (options.logs) LogsListener.start()
            if (options.crashes) {
                Crash.init(appContext!!)
                Crash.start()
            }
            onStarted?.invoke()
        }
    }

    fun stopRecording(closeSession: Boolean = true) {
        if (!isInitialized.get()) return
        if (state != RecorderState.RECORDING) return

        state = RecorderState.STOPPING

        ScreenshotManager.stop()
        LogsListener.stop()
        Crash.stop()
        MessageCollector.stop()
        if (closeSession) SessionRequest.clear()

        state = RecorderState.INITIALIZED
    }

    fun shutdown() {
        stopRecording(closeSession = true)

        lifecycleManager?.let {
            app?.unregisterActivityLifecycleCallbacks(it)
        }
        lifecycleManager = null

        unregisterNetworkCallbacks()

        isInitialized.set(false)
        state = RecorderState.IDLE
    }

    private fun updateCurrentActivity(activity: Activity?) {
        currentActivity = activity
        ScreenshotManager.updateCurrentActivity(activity)
    }
    internal fun onAppForeground() {
        if (autoRecordingEnabled.get() && state == RecorderState.INITIALIZED) {
            startRecording()
        }
    }

    internal fun onAppBackground() {
        if (autoRecordingEnabled.get()) {
            scope.launch {
                delay(800)
                if (startedActivities.get() == 0 && state == RecorderState.RECORDING) {
                    stopRecording(closeSession = false)
                }
            }
        }
    }

    fun isRecording(): Boolean = state == RecorderState.RECORDING

    fun setUserID(userID: String) {
        MessageCollector.sendMessage(ORMobileUserID(iD = userID))
    }

    fun addIgnoredView(view: View) = ScreenshotManager.addSanitizedElement(view)
    fun sanitizeView(view: View) = ScreenshotManager.addSanitizedElement(view)


    fun eventStr(name: String, jsonPayload: String) {
        MessageCollector.sendMessage(ORMobileEvent(name, payload = jsonPayload))
    }

    fun getSessionID(): String = SessionRequest.getSessionId() ?: ""


    private fun getLateMessagesFile(context: Context): File {
        if (lateMessagesFile == null) {
            lateMessagesFile = File(context.filesDir, "lateMessages.dat")
        }
        return lateMessagesFile!!
    }

    private fun checkForLateMessages() {
        val context = appContext ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val file = getLateMessagesFile(context)
            if (!file.exists()) return@launch
            runCatching {
                val crashData = file.readBytes()
                NetworkManager.sendLateMessage(crashData) { success ->
                    if (success) CoroutineScope(Dispatchers.IO).launch { file.delete() }
                }
            }.onFailure {
                DebugUtils.log("Error processing late messages: ${it.message}")
            }
        }
    }
    private fun registerNetworkCallbacks(application: Application) {
        if (networkCallbackRegistered) return
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (state != RecorderState.RECORDING) return
                    val ok = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !options.wifiOnly)
                    if (!ok) {
                        stopRecording(closeSession = false)
                    }
                }
            })
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkCallbacks() {
        if (!networkCallbackRegistered) return
        runCatching {
            connectivityManager?.unregisterNetworkCallback(ConnectivityManager.NetworkCallback())
        }
        networkCallbackRegistered = false
    }
}

fun getCaptureSettings(fps: Int, quality: RecordingQuality): Triple<Int, Int, Int> {
    val limitedFPS = min(max(fps, 1), 60)
    val captureRate = (1000 / limitedFPS).coerceAtLeast(250)

    val imgCompression = when (quality) {
        RecordingQuality.Low -> 1
        RecordingQuality.Standard -> 30
        RecordingQuality.High -> 60
    }
    val imgResolution = when (quality) {
        RecordingQuality.Low -> 144
        RecordingQuality.Standard -> 720
        RecordingQuality.High -> 1080
    }
    return Triple(captureRate, imgCompression, imgResolution)
}

class SanitizableViewGroup(context: Context) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var maxHeight = 0
        var maxWidth = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            maxWidth = maxOf(maxWidth, child.measuredWidth)
            maxHeight = maxOf(maxHeight, child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(maxWidth, widthMeasureSpec), resolveSize(maxHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }
}
