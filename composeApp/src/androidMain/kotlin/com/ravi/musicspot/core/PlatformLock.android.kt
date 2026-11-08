package com.ravi.musicspot.core

actual class PlatformLock actual constructor() {
    @PublishedApi
    internal val lock = Any()

    actual inline fun <R> withLock(block: () -> R): R =
        kotlin.synchronized(lock, block)
}
