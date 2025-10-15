package com.openreplay.tracker.models

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.NetworkManager
import com.openreplay.tracker.managers.UserDefaults
import kotlinx.coroutines.*
import java.io.Serializable
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

object SessionRequest {
    private val params = mutableMapOf<String, Any>()
    private val sessionId = AtomicReference<String?>()
    private val cachedSessionResponse = AtomicReference<SessionResponse?>()

    private const val RETRY_DELAY_MS = 5000L
    private const val MAX_RETRIES = 5
    private var retryCount = 0

    fun create(context: Context, doNotRecord: Boolean, completion: (SessionResponse?) -> Unit) {
        cachedSessionResponse.get()?.let {
            completion(it)
            return
        }

        initializeParams(context, doNotRecord)
        callAPI(completion)
    }

    fun clear() {
        sessionId.set(null)
        cachedSessionResponse.set(null)
    }

    private fun initializeParams(context: Context, doNotRecord: Boolean) {
        val (width, height) = getDeviceResolution(context)
        val deviceModel = Build.DEVICE ?: "Unknown"
        val deviceType = if (isTablet(context)) "tablet" else "mobile"

        params.apply {
            clear()
            put("platform", "android")
            put("width", width)
            put("height", height)
            put("doNotRecord", doNotRecord)
            put("projectKey", OpenReplay.projectKey!!)
            put("trackerVersion", OpenReplay.options.pkgVersion)
            put("revID", "N/A")
            put("userUUID", UserDefaults.userUUID)
            put("userOSVersion", Build.VERSION.RELEASE)
            put("userDevice", deviceModel)
            put("userDeviceType", deviceType)
            put("timestamp", Date().time)
            put("deviceMemory", Runtime.getRuntime().maxMemory() / (1024 * 1024)) // MB
            put("timezone", getTimezone())
        }
    }

    private fun getDeviceResolution(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } catch (e: Exception) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getMetrics(metrics)
                Pair(metrics.widthPixels, metrics.heightPixels)
            }
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun isTablet(context: Context): Boolean {
        return context.resources.configuration.smallestScreenWidthDp >= 600
    }

    private fun callAPI(completion: (SessionResponse?) -> Unit) {
        if (params.isEmpty()) return

        NetworkManager.createSession(params) { sessionResponse ->
            when {
                sessionResponse != null -> {
                    sessionId.set(sessionResponse.sessionID)
                    cachedSessionResponse.set(sessionResponse)
                    retryCount = 0
                    DebugUtils.log("[SessionRequest] Started session: ${sessionResponse.sessionID}")
                    completion(sessionResponse)
                }

                retryCount < MAX_RETRIES -> {
                    retryCount++
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(RETRY_DELAY_MS)
                        callAPI(completion)
                    }
                }

                else -> {
                    DebugUtils.log("[SessionRequest] Failed after $MAX_RETRIES retries.")
                    completion(null)
                }
            }
        }
    }

    fun getSessionId(): String? = sessionId.get()
}

data class SessionResponse(
    val userUUID: String,
    val token: String,
    val imagesHashList: List<String>?,
    val sessionID: String,
    val fps: Int,
    val quality: String,
    val projectID: String
) : Serializable

fun getTimezone(): String {
    val offset = TimeZone.getDefault().getOffset(Date().time) / 1000
    val sign = if (offset >= 0) "+" else "-"
    val hours = abs(offset) / 3600
    val minutes = (abs(offset) % 3600) / 60
    return String.format("UTC%s%02d:%02d", sign, hours, minutes)
}
