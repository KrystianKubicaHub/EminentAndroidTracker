package com.openreplay.tracker.managers

import android.content.Context
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.models.ORMessage
import com.openreplay.tracker.models.script.ORMobileBatchMeta
import com.openreplay.tracker.models.script.ORMobileNetworkCall
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object MessageCollector {
    private val messagesWaiting = mutableListOf<ByteArray>()
    private val messagesWaitingBackup = mutableListOf<ByteArray>()
    private var nextMessageIndex = 0
    private var sendingLastMessages = false
    private const val maxMessagesSize = 500_000
    private var lateMessagesFile: File? = null

    private var sendIntervalFuture: ScheduledFuture<*>? = null
    private val executorService = Executors.newScheduledThreadPool(1)

    private var tick = 0
    private var debouncedMessage: ORMessage? = null
    private var debounceJob: Job? = null

    fun start(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val lateFile = File(context.filesDir, "lateMessages.dat")
            lateMessagesFile = lateFile

            if (lateFile.exists()) {
                try {
                    val lateData = lateFile.readBytes()
                    NetworkManager.sendLateMessage(lateData) { success ->
                        if (success) lateFile.delete()
                    }
                } catch (e: Exception) {
                    DebugUtils.log("[MessageCollector] Error reading late messages: ${e.message}")
                }
            }

            sendIntervalFuture = executorService.scheduleWithFixedDelay({
                flushMessages()
            }, 0, 5, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        sendIntervalFuture?.cancel(true)
        terminate()
    }

    fun sendMessage(message: ORMessage) {
        if (OpenReplay.options.debugLogs) {
            if (!message.toString().contains("Log") && !message.toString().contains("NetworkCall")) {
                DebugUtils.log(message.toString())
            }
            (message as? ORMobileNetworkCall)?.let {
                DebugUtils.log("-->> NetworkCall: ${it.method} ${it.URL}")
            }
        }

        sendRawMessage(message.contentData())
    }

    private fun sendRawMessage(data: ByteArray) {
        executorService.execute {
            if (data.size > maxMessagesSize) {
                DebugUtils.log("[MessageCollector] Single message too large")
                return@execute
            }

            synchronized(messagesWaiting) {
                messagesWaiting.add(data)
            }

            val total = synchronized(messagesWaiting) { messagesWaiting.sumOf { it.size } }
            if (total > maxMessagesSize * 0.8) flushMessages()
        }
    }

    fun sendDebouncedMessage(message: ORMessage) {
        debounceJob?.cancel()
        debouncedMessage = message

        debounceJob = CoroutineScope(Dispatchers.Default).launch {
            delay(2000)
            debouncedMessage?.let {
                sendMessage(it)
                debouncedMessage = null
            }
        }
    }

    private fun flushMessages() {
        val messages = mutableListOf<ByteArray>()
        var sentSize = 0

        synchronized(messagesWaiting) {
            while (messagesWaiting.isNotEmpty() && sentSize + messagesWaiting.first().size <= maxMessagesSize) {
                val msg = messagesWaiting.removeAt(0)
                messages.add(msg)
                sentSize += msg.size
            }
        }

        if (messages.isEmpty()) return

        val batch = ByteArrayOutputStream().apply {
            write(ORMobileBatchMeta(nextMessageIndex.toULong()).contentData())
            messages.forEach { write(it) }
        }

        nextMessageIndex += messages.size
        val batchBytes = batch.toByteArray()

        if (sendingLastMessages && lateMessagesFile?.exists() == true) {
            try {
                lateMessagesFile?.writeBytes(batchBytes)
            } catch (e: IOException) {
                DebugUtils.log("[MessageCollector] Error saving late batch: ${e.message}")
            }
        }

        NetworkManager.sendMessage(batchBytes) { success ->
            if (!success) {
                DebugUtils.log("[MessageCollector] Failed batch; requeueing")
                synchronized(messagesWaiting) { messagesWaiting.addAll(0, messages) }
            } else if (sendingLastMessages) {
                sendingLastMessages = false
                lateMessagesFile?.delete()
            }
        }
    }

    fun syncBuffers() {
        val buf1 = messagesWaiting.size
        val buf2 = messagesWaitingBackup.size
        tick = 0

        if (buf1 > buf2) {
            messagesWaitingBackup.clear()
        } else {
            messagesWaiting.clear()
            messagesWaiting.addAll(messagesWaitingBackup)
            messagesWaitingBackup.clear()
        }

        flushMessages()
    }

    fun cycleBuffer() {
        if (tick % 2 == 0) {
            messagesWaiting.clear()
        } else {
            messagesWaitingBackup.clear()
        }
        tick += 1
    }

    private fun terminate() {
        if (sendingLastMessages) return
        sendingLastMessages = true
        executorService.execute { flushMessages() }
    }
}
