package com.openreplay.tracker.listeners

import android.content.Context
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.NetworkManager
import com.openreplay.tracker.models.script.ORMobileCrash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

object Crash {
    private var fileUrl: File? = null
    private var isActive = false
    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context) {
        contextRef = WeakReference(context.applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheDir = context.cacheDir
                fileUrl = File(cacheDir, "ASCrash.dat")

                val f = fileUrl ?: return@launch
                if (f.exists()) {
                    val crashData = f.readBytes()
                    NetworkManager.sendLateMessage(crashData) { success ->
                        if (success) f.delete()
                    }
                }
            } catch (e: Exception) {
                DebugUtils.log("[Crash] Error in init: ${e.message}")
            }
        }
    }

    fun start() {
        if (isActive) return
        isActive = true

        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            DebugUtils.log("[Crash] Captured crash: ${e.localizedMessage}")
            val message = ORMobileCrash(
                name = e.javaClass.name,
                reason = e.localizedMessage ?: "",
                stacktrace = e.stackTrace.joinToString("\n") { it.toString() }
            )

            val crashBytes = message.contentData()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val file = fileUrl ?: contextRef?.get()?.let {
                        File(it.cacheDir, "ASCrash.dat")
                    }
                    file?.writeBytes(crashBytes)

                    NetworkManager.sendMessage(crashBytes) { success ->
                        if (success) {
                            file?.delete()
                        } else {
                            DebugUtils.log("[Crash] Failed to send crash, saved locally")
                        }
                    }
                } catch (ex: Exception) {
                    DebugUtils.log("[Crash] Error saving or sending crash: ${ex.message}")
                }
            }
        }
    }

    fun sendLateError(exception: Exception) {
        val message = ORMobileCrash(
            name = exception.javaClass.name,
            reason = exception.localizedMessage ?: "",
            stacktrace = exception.stackTrace.joinToString("\n") { it.toString() }
        )

        val crashBytes = message.contentData()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                NetworkManager.sendLateMessage(crashBytes) { success ->
                    if (success) {
                        fileUrl?.delete()
                    }
                }
            } catch (e: Exception) {
                DebugUtils.log("[Crash] Error sending late error: ${e.message}")
            }
        }
    }

    fun stop() {
        if (!isActive) return
        Thread.setDefaultUncaughtExceptionHandler(null)
        isActive = false
    }
}
