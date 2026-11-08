package com.ravi.musicspot.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer

/**
 * 1.0.7: background work that fails must not kill the app. Without a handler, any exception escaping a launch on a
 * SupervisorJob scope goes to the thread's uncaught handler, i.e. a crash.
 */
val BackgroundFaults = kotlinx.coroutines.CoroutineExceptionHandler { ctx, e ->
    println("UpZe: background job failed (${ctx[kotlinx.coroutines.CoroutineName]?.name ?: "io"}): ${e.message}")
}

/** Process-wide background scope for persistence. */
val IoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + BackgroundFaults)

/**
 * A JSON file written atomically (tmp → fsync/atomic write → rename) and debounced.
 */
class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val debounceMs: Long = 400,
    private val holdWritesUntilRead: Boolean = true,
) {
    private val mutex = Mutex()
    private var pending: Job? = null
    private var latest: (() -> T)? = null
    @Volatile private var readDone = !holdWritesUntilRead

    init { register(this) }

    private val tmp get() = File(file.parentFile ?: File(""), file.name + ".tmp")

    /** Blocking or synchronous read. */
    fun read(): T? {
        val v = readInternal()
        markRead()
        return v
    }

    suspend fun load(): T? = withContext(Dispatchers.Default) {
        mutex.withLock { read() }
    }

    private fun readInternal(): T? {
        val candidates = listOfNotNull(file.takeIf { it.isFile }, tmp.takeIf { it.isFile })
        for (src in candidates) {
            val text = src.readText()
            if (text.isNotBlank()) {
                val v = runCatching { Http.json.decodeFromString(serializer, text) }.getOrNull()
                if (v != null) return v
                runCatching {
                    val corrupt = File(src.parentFile ?: File(""), src.name + ".corrupt")
                    src.renameTo(corrupt)
                }
            }
        }
        return null
    }

    private fun markRead() {
        if (readDone) return
        readDone = true
        if (latest != null) schedule()
    }

    fun writeNow(value: T) {
        writeInternal(value)
    }

    private fun writeInternal(value: T) {
        file.parentFile?.mkdirs()
        val text = Http.json.encodeToString(serializer, value)
        file.writeText(text)
    }

    /** Debounced background write of the latest value. */
    fun save(value: () -> T) {
        latest = value
        if (!readDone) return
        schedule()
    }

    private fun schedule() {
        pending?.cancel()
        pending = IoScope.launch {
            delay(debounceMs)
            writeLatest()
        }
    }

    private suspend fun writeLatest() {
        mutex.withLock {
            val f = latest.also { latest = null } ?: return
            val ok = runCatching {
                val v = f()
                writeInternal(v)
            }.onFailure {
                CrashGuard.nonFatal(null, "JsonFile.write ${file.name}", it)
            }.isSuccess
            if (!ok && latest == null) {
                latest = f
            }
        }
    }

    suspend fun flush() {
        if (!readDone) return
        pending?.cancel()
        pending = null
        withContext(Dispatchers.Default) {
            writeLatest()
        }
    }

    companion object {
        private val all = mutableListOf<JsonFile<*>>()

        private fun register(f: JsonFile<*>) {
            all.add(f)
        }

        fun flushAll(): Job = IoScope.launch {
            val files = all.toList()
            files.forEach { runCatching { it.flush() } }
        }
    }
}
