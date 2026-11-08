package com.ravi.musicspot.core

object CrashGuard {
    /** The most recent saved crash report (previous launch's if one was archived), or null when none exists. */
    fun previousReport(): String? = runCatching {
        val dir = PlatformIO.appDir()
        PlatformIO.readText("$dir/crash-prev.txt") ?: PlatformIO.readText("$dir/crash.txt")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun nonFatal(ctx: Any?, tag: String, error: Throwable) {
        println("[$tag] Non-fatal error: ${error.message}")
        runCatching {
            PlaybackLog.add("Non-fatal in $tag: ${error.message}", tag)
        }
    }
}
