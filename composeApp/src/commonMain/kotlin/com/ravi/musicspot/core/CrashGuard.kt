package com.ravi.musicspot.core

object CrashGuard {
    fun nonFatal(ctx: Any?, tag: String, error: Throwable) {
        println("[$tag] Non-fatal error: ${error.message}")
        runCatching {
            PlaybackLog.log("Non-fatal in $tag: ${error.message}")
        }
    }
}
