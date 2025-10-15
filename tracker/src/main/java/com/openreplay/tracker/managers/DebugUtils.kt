package com.openreplay.tracker.managers

import com.openreplay.tracker.OpenReplay

object DebugUtils {
    fun error(str: String) {
        println("x Error: $str")
    }

    fun log(str: String) {
        if (OpenReplay.options.debugLogs) {
            println("OpenReplay: $str")
        }
    }

    fun error(e: Throwable) {
        println("OpenReplay Error: ${e.localizedMessage}")
    }
}
