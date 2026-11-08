package com.ravi.musicspot.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSStringEncoding
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeData
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
actual object PlatformIO {
    actual fun appDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        return (paths.firstOrNull() as? String) ?: ""
    }

    actual fun cacheDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        return (paths.firstOrNull() as? String) ?: ""
    }

    actual fun readText(path: String): String? {
        val nsStr = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        return nsStr as? String
    }

    actual fun writeTextAtomic(path: String, text: String) {
        val nsStr = text as NSString
        nsStr.writeToFile(path, true, NSUTF8StringEncoding, null)
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        val nsData = if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
        } else {
            NSData()
        }
        nsData.writeToFile(path, true)
    }

    actual fun appendBytes(path: String, bytes: ByteArray) {
        if (!exists(path)) {
            writeBytes(path, bytes)
            return
        }
        val handle = NSFileHandle.fileHandleForWritingAtPath(path) ?: return
        try {
            handle.seekToEndOfFile()
            val nsData = if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
                }
            } else {
                NSData()
            }
            handle.writeData(nsData)
        } finally {
            handle.closeFile()
        }
    }

    actual fun rename(from: String, to: String): Boolean {
        if (exists(to)) {
            delete(to)
        }
        return NSFileManager.defaultManager.moveItemAtPath(from, to, null)
    }

    actual fun exists(path: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun delete(path: String): Boolean =
        NSFileManager.defaultManager.removeItemAtPath(path, null)

    @Suppress("UNCHECKED_CAST")
    actual fun listFiles(dir: String): List<String> =
        (NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null) as? List<String>) ?: emptyList()

    actual fun fileSize(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        return (attrs?.get("NSFileSize") as? Long) ?: 0L
    }

    actual fun lastModified(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        val date = attrs?.get("NSFileModificationDate") as? platform.Foundation.NSDate
        return date?.let { (it.timeIntervalSince1970 * 1000).toLong() } ?: 0L
    }

    actual fun mkdirs(path: String): Boolean =
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
}
