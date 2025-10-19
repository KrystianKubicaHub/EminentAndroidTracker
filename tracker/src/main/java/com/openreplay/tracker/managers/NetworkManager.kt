package com.openreplay.tracker.managers

import android.content.Context
import android.net.TrafficStats
import com.google.gson.Gson
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.models.SessionResponse
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPOutputStream

object NetworkManager {
    private const val START_URL = "/v1/mobile/start"
    private const val INGEST_URL = "/v1/mobile/i"
    private const val LATE_URL = "/v1/mobile/late"
    private const val IMAGES_URL = "/v1/mobile/images"
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var baseUrl = "https://api.openreplay.com/ingest"
    @Volatile
    var sessionId: String? = null
    @Volatile
    var projectId: String? = null
    @Volatile
    var projectKey: String? = null

    @Volatile
    var token: String? = null
    private var writeToFile = false
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        if (!this::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }
    private fun getAppContext(): Context {
        if (!this::appContext.isInitialized) {
            throw IllegalStateException("NetworkManager must be initialized with a Context before usage")
        }
        return appContext
    }

    private suspend fun createRequest(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String>? = null
    ): HttpURLConnection = withContext(Dispatchers.IO) {
        val url = URL(baseUrl + path)
        val connection = url.openConnection() as HttpURLConnection

        connection.connectTimeout = 10000
        connection.readTimeout = 30000

        try {
            TrafficStats.setThreadStatsTag(1000)
            connection.requestMethod = method
            connection.doInput = true
            connection.useCaches = false
            headers?.forEach { (k, v) -> connection.setRequestProperty(k, v) }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        } finally {
            TrafficStats.clearThreadStatsTag()
        }
        return@withContext connection
    }
    fun createSession(params: Map<String, Any>, completion: (SessionResponse?) -> Unit) {
        networkScope.launch {
            if (writeToFile) {
                token = "writeToFile"
                withContext(Dispatchers.Main) { completion(null) }
                return@launch
            }

            val json = Gson().toJson(params).toByteArray()
            try {
                val request = createRequest(
                    "POST",
                    START_URL,
                    body = json,
                    headers = mapOf("Content-Type" to "application/json; charset=utf-8")
                )

                val body = request.inputStream.bufferedReader().readText()
                if (body.isNotEmpty()) {
                    val sessionResponse = Gson().fromJson(body, SessionResponse::class.java)
                    token = sessionResponse.token
                    sessionId = sessionResponse.sessionID
                    projectId = sessionResponse.projectID
                    projectKey = params["projectKey"] as? String
                    withContext(Dispatchers.Main) { completion(sessionResponse) }
                } else {
                    DebugUtils.log("[Network] Empty body for /start")
                    withContext(Dispatchers.Main) { completion(null) }
                }
            } catch (e: Exception) {
                DebugUtils.log("[Network] createSession error: ${e.message}")
                withContext(Dispatchers.Main) { completion(null) }
            }
        }
    }
    fun sendMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        networkScope.launch {
            if (writeToFile) {
                appendLocalFile(content)
                return@launch
            }

            val compressed = runCatching { compressData(content) }
                .onSuccess { DebugUtils.log("[Network] Compressed ${content.size}B -> ${it.size}B") }
                .getOrElse {
                    DebugUtils.log("[Network] Compression failed: ${it.message}")
                    content
                }

            val request = createRequest(
                "POST",
                INGEST_URL,
                body = compressed,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Encoding" to "gzip",
                    "Content-Type" to "application/octet-stream"
                )
            )

            try {
                request.connect()
                when (request.responseCode) {
                    in 200..299 -> {
                        DebugUtils.log("[Network] Message sent OK")
                        withContext(Dispatchers.Main) { completion(true) }
                    }
                    401 -> {
                        DebugUtils.log("[Network] 401 unauthorized – token expired")
                        OpenReplay.stopRecording(closeSession = true)
                        withContext(Dispatchers.Main) { completion(false) }
                    }
                    else -> {
                        DebugUtils.log("[Network] sendMessage failed: ${request.responseCode}")
                        withContext(Dispatchers.Main) { completion(false) }
                    }
                }
            } catch (e: Exception) {
                DebugUtils.log("[Network] sendMessage exception: ${e.message}")
                withContext(Dispatchers.Main) { completion(false) }
            } finally {
                request.disconnect()
            }
        }
    }
    private fun compressData(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
    private fun appendLocalFile(data: ByteArray) {
        networkScope.launch {
            if (OpenReplay.options.debugLogs) {
                val file = File(getAppContext().filesDir, "session.dat")
                try {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                    FileOutputStream(file, true).use { it.write(data) }
                    DebugUtils.log("[Network] Data appended to ${file.absolutePath}")
                } catch (e: IOException) {
                    DebugUtils.log("[Network] File append error: ${e.message}")
                }
            }
        }
    }
    fun sendLateMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        networkScope.launch {
            val tok = UserDefaults.lastToken ?: run {
                DebugUtils.log("[Network] No last token for sendLateMessage")
                withContext(Dispatchers.Main) { completion(false) }
                return@launch
            }

            val request = createRequest(
                "POST",
                LATE_URL,
                body = content,
                headers = mapOf("Authorization" to "Bearer $tok")
            )

            try {
                request.connect()
                val ok = request.responseCode in 200..299
                DebugUtils.log("[Network] sendLateMessage -> $ok (${request.responseCode})")
                withContext(Dispatchers.Main) { completion(ok) }
            } catch (e: Exception) {
                DebugUtils.log("[Network] sendLateMessage exception: ${e.message}")
                withContext(Dispatchers.Main) { completion(false) }
            } finally {
                request.disconnect()
            }
        }
    }
    private fun buildMultipartBody(
        boundary: String,
        formFields: Map<String, String>,
        fileField: Pair<String, Pair<String, ByteArray>>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = out.bufferedWriter()

        formFields.forEach { (n, v) ->
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n")
        }

        val (fileName, fileData) = fileField.second
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"${fileField.first}\"; filename=\"$fileName\"\r\n")
        writer.write("Content-Type: application/gzip\r\n\r\n")
        writer.flush()
        out.write(fileData)
        writer.write("\r\n--$boundary--\r\n")
        writer.flush()
        return out.toByteArray()
    }

    fun sendImages(projectKey: String, images: ByteArray, name: String, completion: (Boolean) -> Unit) {
        networkScope.launch {
            val tok = token ?: run {
                DebugUtils.log("[Network] No token for sendImages")
                withContext(Dispatchers.Main) { completion(false) }
                return@launch
            }

            val boundary = "Boundary-${UUID.randomUUID()}"
            val body = buildMultipartBody(
                boundary,
                formFields = mapOf("projectKey" to projectKey),
                fileField = "batch" to Pair(name, images)
            )

            val request = createRequest(
                "POST",
                IMAGES_URL,
                body = body,
                headers = mapOf(
                    "Authorization" to "Bearer $tok",
                    "Content-Type" to "multipart/form-data; boundary=$boundary"
                )
            )

            try {
                request.connect()
                val ok = request.responseCode in 200..299
                DebugUtils.log("[Network] sendImages -> $ok (${request.responseCode})")
                withContext(Dispatchers.Main) { completion(ok) }
            } catch (e: Exception) {
                DebugUtils.log("[Network] sendImages exception: ${e.message}")
                withContext(Dispatchers.Main) { completion(false) }
            } finally {
                request.disconnect()
            }
        }
    }
}
