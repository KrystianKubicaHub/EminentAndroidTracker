package com.openreplay.tracker.models

import com.openreplay.tracker.models.script.ORMessageType
import com.openreplay.tracker.models.script.fromValues

open class ORMessage(
    val messageRaw: UByte,
    val message: ORMessageType?,
    val timestamp: ULong
) {
    constructor(messageType: ORMessageType) : this(
        messageRaw = messageType.id,
        message = messageType,
        timestamp = System.currentTimeMillis().toULong() // Conversion to milliseconds since epoch
    )


    fun prefixData(): ByteArray {
        return fromValues(messageRaw, timestamp)
    }

    open fun contentData(): ByteArray {
        throw NotImplementedError("This method should be overridden")
    }
}
