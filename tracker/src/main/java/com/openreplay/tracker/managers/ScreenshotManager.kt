package com.openreplay.tracker.managers

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.AbstractComposeView
import com.openreplay.tracker.SanitizableViewGroup
import com.openreplay.tracker.managers.NetworkManager.sessionId
import com.openreplay.tracker.models.OROptions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.roundToInt

object ScreenshotManager {
    private var firstTs: String = ""
    private var lastTs: String = ""
    private var quality: Int = 30
    private var minResolution: Int = 720

    private val sanitizedElements: MutableList<View> = mutableListOf()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> DebugUtils.error(t) }
    )
    private var job: Job? = null

    @Volatile private var sessionRunning = AtomicBoolean(false)
    @Volatile private var uiContextRef: WeakReference<Context> = WeakReference(null)
    @Volatile private var currentActivityRef: WeakReference<Activity> = WeakReference(null)

    private var frameIntervalMs: Long = 500L

    fun setSettings(settings: Triple<Int, Int, Int>) {
        val (interval, q, res) = settings
        frameIntervalMs = interval.coerceAtLeast(250).toLong()
        quality = q
        minResolution = res
    }
    fun logDebug(string: String){
        println("OpenReplay ::: ScreenshotManager: $string")
    }

    fun start(context: Context, startTs: Long, options: OROptions) {
        if (sessionRunning.get()) {
            logDebug("DEBUG: session already running, skipping start()")
            return
        }
        sessionRunning.set(true)

        uiContextRef = WeakReference(context.applicationContext)
        firstTs = startTs.toString()

        runCatching {
            val screenshotsDir = getScreenshotFolder()
            val archivesDir = getArchiveFolder()
            val removedScreens = screenshotsDir.listFiles()?.onEach { it.delete() }?.size ?: 0
            val removedArchives = archivesDir.listFiles()?.onEach { it.delete() }?.size ?: 0
            logDebug("DEBUG: cleaned screenshots=$removedScreens, archives=$removedArchives")
        }.onFailure {
            logDebug("DEBUG: failed to clean folders - ${it.message}")
        }

        logDebug(
            "DEBUG: ScreenshotManager starting at ${System.currentTimeMillis()} " +
                    "with FPS=${options.fps}, quality=${options.screenshotQuality}, " +
                    "interval=${frameIntervalMs}ms"
        )

        job?.cancel()
        job = scope.launch {
            while (isActive && sessionRunning.get()) {
                delay(frameIntervalMs)

                val bmp = captureScreenshot()
                if (bmp == null) {
                    logDebug("DEBUG: captureScreenshot() returned null, skipping frame")
                    continue
                }

                logDebug("DEBUG: captured bitmap size=${bmp.width}x${bmp.height}")

                saveAndMaybeArchive(bmp, chunk = 10)
            }
        }
    }


    fun stop() {
        if (!sessionRunning.getAndSet(false)) return
        job?.cancel()
        scope.coroutineContext.cancelChildren()

        scope.launch {
            runCatching {
                archivateFolder(getScreenshotFolder())
                sendScreenshotArchives()
            }.onFailure { DebugUtils.error("Error during termination: ${it.message}") }
        }
    }

    fun updateCurrentActivity(activity: Activity?) {
        currentActivityRef = WeakReference(activity)
    }

    fun addSanitizedElement(view: View) {
        DebugUtils.log("Sanitize view: $view")
        sanitizedElements.add(view)
    }

    fun removeSanitizedElement(view: View) {
        sanitizedElements.remove(view)
    }

    private suspend fun saveAndMaybeArchive(bitmap: Bitmap, chunk: Int) = withContext(Dispatchers.IO) {
        try {
            val folder = getScreenshotFolder()
            if (!folder.exists()) {
                folder.mkdirs()
                DebugUtils.log("::: ScreenshotManager: DEBUG: Created screenshot folder: ${folder.absolutePath}")
            }

            val filename = "${System.currentTimeMillis()}.jpeg"
            val file = File(folder, filename)
            val compressed = compress(bitmap)
            FileOutputStream(file).use { out ->
                out.write(compressed)
            }

            DebugUtils.log("::: ScreenshotManager: DEBUG: Saved screenshot → ${file.name} (${compressed.size / 1024} KB)")

            val count = folder.listFiles()?.size ?: 0
            DebugUtils.log("::: ScreenshotManager: DEBUG: Total screenshots in folder: $count (chunk limit = $chunk)")

            if (count >= chunk) {
                DebugUtils.log("::: ScreenshotManager: DEBUG: Reached $count screenshots, starting archivation...")
                archivateFolder(folder)
            } else {
                DebugUtils.log("::: ScreenshotManager: DEBUG: Not archiving yet — need ${chunk - count} more screenshots.")
            }

        } catch (e: Exception) {
            DebugUtils.log("::: ScreenshotManager: ERROR in saveAndMaybeArchive → ${e.message}")
        }
    }


    private suspend fun sendScreenshotArchives() = withContext(Dispatchers.IO) {
        val archives = getArchiveFolder().listFiles().orEmpty()

        if (archives.isEmpty()) {
            DebugUtils.log("[Screenshots] No archives found to upload.")
            return@withContext
        }

        val key = NetworkManager.projectKey
        val tokenSet = NetworkManager.token != null
        DebugUtils.log(
            "[Screenshots] Preparing to send ${archives.size} archives; " +
                    "projectKey=$key, tokenSet=$tokenSet, thread=${Thread.currentThread().name}"
        )
        archives.forEachIndexed { i, archive ->
            DebugUtils.log("[Screenshots] #$i ${archive.name} size=${archive.length()}B")
        }


        archives.forEach { archive ->
            try {

                if (key.isNullOrBlank()) {
                    DebugUtils.log("[Screenshots] Skipping ${archive.name} – projectKey is null or blank")
                    return@forEach
                }

                val bytes = archive.readBytes()
                DebugUtils.log("[Screenshots] Uploading ${archive.name} (${bytes.size}B)...")

                NetworkManager.sendImages(
                    projectKey = key,
                    images = bytes,
                    name = archive.name
                ) { success ->
                    if (success) {
                        DebugUtils.log("[Screenshots]ploaded ${archive.name} successfully — keeping file for inspection")

                        // scope.launch(Dispatchers.IO) { archive.deleteSafely() }
                    } else {
                        DebugUtils.log("[Screenshots] Upload failed for ${archive.name}")
                    }
                }
            } catch (e: Exception) {
                DebugUtils.log("[Screenshots] Exception while sending ${archive.name}: ${e.message}")
            }
        }
    }



    private fun archivateFolder(folder: File) {
        try {
            val screenshots = folder.listFiles().orEmpty().sortedBy { it.lastModified() }
            if (screenshots.isEmpty()) {
                DebugUtils.log("::: ScreenshotManager: DEBUG: archivateFolder() → no screenshots found, skipping.")
                return
            }

            val totalSizeKb = screenshots.sumOf { it.length() } / 1024
            DebugUtils.log("::: ScreenshotManager: DEBUG: archivateFolder() → Found ${screenshots.size} screenshots, total=${totalSizeKb}KB")

            val combined = ByteArrayOutputStream()

            GzipCompressorOutputStream(combined).use { gzos ->
                TarArchiveOutputStream(gzos).use { tar ->
                    screenshots.forEachIndexed { index, jpeg ->
                        try {
                            lastTs = jpeg.nameWithoutExtension
                            val filename = "${firstTs}_1_${jpeg.nameWithoutExtension}.jpeg"
                            val data = jpeg.readBytes()
                            val entry = TarArchiveEntry(filename).apply { size = data.size.toLong() }
                            tar.putArchiveEntry(entry)
                            ByteArrayInputStream(data).copyTo(tar)
                            tar.closeArchiveEntry()

                            DebugUtils.log("::: ScreenshotManager: DEBUG: Added file [${index + 1}/${screenshots.size}] → ${jpeg.name} (${data.size / 1024}KB)")
                        } catch (e: Exception) {
                            DebugUtils.log("::: ScreenshotManager: ERROR: Failed to add ${jpeg.name} to archive: ${e.message}")
                        }
                    }
                }
            }

            val archive = File(getArchiveFolder(), "$sessionId-$lastTs.tar.gz")
            FileOutputStream(archive).use { it.write(combined.toByteArray()) }

            DebugUtils.log("::: ScreenshotManager: DEBUG: Archive created → ${archive.absolutePath} (${archive.length() / 1024}KB)")
            DebugUtils.log("::: ScreenshotManager: DEBUG: Archive contains ${screenshots.size} screenshots, total before compression=${totalSizeKb}KB")

            // scope.launch(Dispatchers.IO) { screenshots.forEach { it.deleteSafely() } }
            // DebugUtils.log("::: ScreenshotManager: DEBUG: Deleted ${screenshots.size} screenshots after archivation")

            scope.launch(Dispatchers.IO) {
                DebugUtils.log("::: ScreenshotManager: DEBUG: Triggering sendScreenshotArchives() after archivation...")
                sendScreenshotArchives()
            }

        } catch (e: Exception) {
            DebugUtils.log("::: ScreenshotManager: ERROR: archivateFolder() failed → ${e.message}")
        }
    }


    private fun getArchiveFolder(): File {
        val ctx = uiContextRef.get() ?: throw IllegalStateException("No context")
        return File(ctx.filesDir, "archives").apply { mkdirs() }
    }

    private fun getScreenshotFolder(): File {
        val ctx = uiContextRef.get() ?: throw IllegalStateException("No context")
        return File(ctx.filesDir, "screenshots").apply { mkdirs() }
    }


    private suspend fun captureScreenshot(): Bitmap? {
        val activity = currentActivityRef.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null

        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(1200) {
                suspendCancellableCoroutine { cont ->
                    val window = activity.window ?: run { cont.resume(null); return@suspendCancellableCoroutine }
                    val rootView = window.decorView.rootView

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val bitmap = Bitmap.createBitmap(
                            rootView.width.coerceAtLeast(1),
                            rootView.height.coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        val location = IntArray(2).also { rootView.getLocationInWindow(it) }
                        val handler = Handler(activity.mainLooper)

                        var delivered = false
                        val timeout = Runnable {
                            if (!delivered) {
                                delivered = true
                                cont.resume(oldViewToBitmap(rootView))
                            }
                        }
                        handler.postDelayed(timeout, 500)

                        runCatching {
                            PixelCopy.request(
                                window,
                                Rect(location[0], location[1], location[0] + rootView.width, location[1] + rootView.height),
                                bitmap,
                                { result ->
                                    handler.removeCallbacks(timeout)
                                    if (delivered) return@request
                                    delivered = true
                                    if (result == PixelCopy.SUCCESS) {
                                        cont.resume(bitmap)
                                    } else {
                                        cont.resume(oldViewToBitmap(rootView))
                                    }
                                },
                                handler
                            )
                        }.onFailure {
                            handler.removeCallbacks(timeout)
                            if (!delivered) {
                                delivered = true
                                cont.resume(null)
                            }
                        }
                    } else {
                        cont.resume(oldViewToBitmap(rootView))
                    }
                }
            }
        }
    }

    private fun oldViewToBitmap(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is AbstractComposeView) child.draw(canvas)
            }
        }

        sanitizedElements.forEach { sanitized ->
            if (sanitized.visibility == View.VISIBLE && sanitized.isAttachedToWindow) {
                val loc = IntArray(2)
                sanitized.getLocationInWindow(loc)
                val rootLoc = IntArray(2)
                view.getLocationInWindow(rootLoc)
                val x = (loc[0] - rootLoc[0]).toFloat()
                val y = (loc[1] - rootLoc[1]).toFloat()
                canvas.save()
                canvas.translate(x, y)
                canvas.drawRect(0f, 0f, sanitized.width.toFloat(), sanitized.height.toFloat(), maskPaint)
                canvas.restore()
            }
        }

        fun iterateCompose(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    when (c) {
                        is SanitizableViewGroup -> {
                            val loc = IntArray(2)
                            c.getLocationInWindow(loc)
                            val rootLoc = IntArray(2)
                            view.getLocationInWindow(rootLoc)
                            val x = (loc[0] - rootLoc[0]).toFloat()
                            val y = (loc[1] - rootLoc[1]).toFloat()
                            canvas.save()
                            canvas.translate(x, y)
                            canvas.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), maskPaint)
                            canvas.restore()
                        }
                        is ViewGroup -> iterateCompose(c)
                    }
                }
            }
        }
        iterateCompose(view)

        return bmp
    }

    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        shader = BitmapShader(createCrossStripedPattern(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun createCrossStripedPattern(): Bitmap {
        val size = 80
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 6f }
        c.drawColor(Color.WHITE)
        fun drawDiag() {
            var i = -size
            while (i < size * 2) {
                c.drawLine(i.toFloat(), 0f, (i + size).toFloat(), size.toFloat(), p)
                i += 24
            }
        }
        drawDiag()
        c.rotate(90f, size / 2f, size / 2f)
        drawDiag()
        return b
    }

    private suspend fun compress(bmp: Bitmap): ByteArray = withContext(Dispatchers.Default) {
        ByteArrayOutputStream().use { out ->
            val aspect = bmp.height.toFloat() / bmp.width.toFloat()
            val h = (minResolution * aspect).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, minResolution, h, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, out)
            } else {
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            out.toByteArray()
        }
    }

    private fun File.deleteSafely() {
        if (exists()) runCatching { delete() }.onFailure {
            DebugUtils.error("Error deleting file: ${it.message}")
        }
    }
}
