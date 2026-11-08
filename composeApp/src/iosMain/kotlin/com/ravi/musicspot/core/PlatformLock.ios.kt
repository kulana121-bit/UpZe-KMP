package com.ravi.musicspot.core

import platform.Foundation.NSRecursiveLock

actual class PlatformLock actual constructor() {
    @PublishedApi
    internal val lock = NSRecursiveLock()

    actual inline fun <R> withLock(block: () -> R): R {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
