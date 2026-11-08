package com.ravi.musicspot.core

object CrashGuard {
    const val FILE = "crash.txt"
    const val PREV_FILE = "crash-prev.txt"

    /** Where the latest crash report is written (same file the original Android CrashGuard used). */
    fun crashFile(): File = File(PlatformIO.appDir(), FILE)

    /** The archived report from the crash before last (Settings › About › Last crash report). */
    fun previousFile(): File = File(PlatformIO.appDir(), PREV_FILE)

    /** Moves the shown report aside (kept as crash-prev.txt for Settings › About › Last crash report). */
    fun archive() {
        runCatching {
            val f = crashFile()
            if (!f.isFile) return
            val prev = previousFile()
            prev.delete()
            if (!f.renameTo(prev)) { prev.writeText(f.readText()); f.delete() }
        }
    }

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
