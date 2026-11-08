package com.ravi.musicspot.core

/**
 * Cross-platform reentrant locking mechanism for Kotlin Multiplatform.
 * On Android/JVM: uses standard monitor synchronization.
 * On iOS/Native: uses NSRecursiveLock.
 */
expect class PlatformLock() {
    inline fun <R> withLock(block: () -> R): R
}

/**
 * Convenience function replacing Java/JVM synchronized(lock) { ... } in common code.
 */
inline fun <R> platformSynchronized(lock: PlatformLock, block: () -> R): R = lock.withLock(block)
