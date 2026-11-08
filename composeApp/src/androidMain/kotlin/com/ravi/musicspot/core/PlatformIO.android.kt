package com.ravi.musicspot.core

import java.io.File as JavaFile
import java.io.FileOutputStream

actual object PlatformIO {
    @Volatile var customAppDir: String? = null
    @Volatile var customCacheDir: String? = null

    actual fun appDir(): String = customAppDir ?: "/data/data/com.ravi.musicspot/files"
    actual fun cacheDir(): String = customCacheDir ?: "/data/data/com.ravi.musicspot/cache"

    actual fun readText(path: String): String? {
        val f = JavaFile(path)
        return if (f.isFile) runCatching { f.readText() }.getOrNull() else null
    }

    actual fun writeTextAtomic(path: String, text: String) {
        val f = JavaFile(path)
        f.parentFile?.mkdirs()
        val tmp = JavaFile(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) {
                throw java.io.IOException("Failed to rename atomic file to $path")
            }
        }
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        val f = JavaFile(path)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { it.write(bytes) }
    }

    actual fun appendBytes(path: String, bytes: ByteArray) {
        val f = JavaFile(path)
        f.parentFile?.mkdirs()
        FileOutputStream(f, true).use { it.write(bytes) }
    }

    actual fun rename(from: String, to: String): Boolean {
        val src = JavaFile(from)
        val dst = JavaFile(to)
        if (dst.exists()) dst.delete()
        return src.renameTo(dst)
    }

    actual fun exists(path: String): Boolean = JavaFile(path).exists()

    actual fun delete(path: String): Boolean = JavaFile(path).delete()

    actual fun listFiles(dir: String): List<String> =
        JavaFile(dir).list()?.toList() ?: emptyList()

    actual fun fileSize(path: String): Long = JavaFile(path).length()

    actual fun lastModified(path: String): Long = JavaFile(path).lastModified()

    actual fun mkdirs(path: String): Boolean = JavaFile(path).mkdirs()
}
