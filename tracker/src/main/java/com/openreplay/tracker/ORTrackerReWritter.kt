package com.openreplay.tracker

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.view.GestureDetector
import com.openreplay.tracker.listeners.LifecycleManager
import com.openreplay.tracker.managers.NetworkManager
import com.openreplay.tracker.managers.UserDefaults
import com.openreplay.tracker.models.OROptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("StaticFieldLeak")
object ORTrackerReWritter {
    var projectKey: String? = null
        private set

    var options: OROptions = OROptions.defaults
        private set
    var serverUrl: String
        get() = NetworkManager.baseUrl //
        set(value){
            NetworkManager.baseUrl = value
        }
    private var app: Application? = null //

    private var appContext: Context? = null

    private var lifecycleManager: LifecycleManager? = null

    private var networkCallvackRegistred = false

    private var connectivityManager: ConnectivityManager? = null


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = MainScope()


    private var state: RecorderState = RecorderState.IDLE //

    private val isInitialized = AtomicBoolean(false) //atomic

    private val autoRecordingEnabled = AtomicBoolean(false)

    private var sessionStartTs: Long = 0L

    private var lateMessageFile: File? = null

    @Volatile
    private var currentActivity: Activity? = null

    private var gestureDetector: GestureDetector? = null

    private val startedActivities = AtomicInteger(0)

    private var lastBecomeBackgroundAt: Long = 0L

    fun initialize(context: Context, projectKey: String, options: OROptions = OROptions.defaults){
        val application = (context.applicationContext) as? Application ///
            ?: throw IllegalArgumentException("OpenReplay.initialize requires Application context")

        if(isInitialized.getAndSet(true)) { //
            this.options = this.options.merge(options)
            return
        }

        this.app = application
        this.appContext = application.applicationContext
        this.projectKey = projectKey
        this.options = options

        NetworkManager.initialize(application)
        CoroutineScope(Dispatchers.IO).launch{ UserDefaults.init(application) }

        lifecycleManager = LifecycleManager(application){ event, activity ->
            when(event){
                LifecycleManager.Event.ActivityStarted -> {
                    startedActivities.incrementAndGet()
                    updateCurrentActivity(activity)
                    onAppForeground()
                }
                LifecycleManager.Event.ActivityResumed -> {
                    updateCurrentActivity(activity)
                }
                LifecycleManager.Event.ActivityStopped -> {
                    val left = startedActivities.decrementAndGet().coerceAtLeast(0)
                    if(left == 0){
                        lastBecomeBackgroundAt = System.currentTimeMillis()
                        onAppBackground()
                    }
                }
                else -> {
                    //elee i don't know
                }
            }
        }.also {
            application.registerActivityLifecycleCallbacks(it) //
        }
        registerNetworkCallbacks(application)

        checkForLateMessages()
        state = RecorderState.INITIALIZED
    }

    private fun checkForLateMessages(){

    }

    private fun registerNetworkCallbacks(application: Application){

    }

    private fun onAppBackground() {

    }

    private fun onAppForeground() {
    }

    private fun updateCurrentActivity(activity: Activity) {


    }

    enum class RecorderState{ IDLE, INITIALIZED, RECORDING, STOPPING}
}

