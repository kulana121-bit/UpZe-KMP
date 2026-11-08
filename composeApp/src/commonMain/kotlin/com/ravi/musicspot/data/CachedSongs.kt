package com.ravi.musicspot.data

import kotlin.concurrent.Volatile

import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.File
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.core.JsonFile
import com.ravi.musicspot.core.PlatformIO
import com.ravi.musicspot.core.currentTimeMillis
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.Resolved
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Pure KMP "Cached songs" manager. Keeps an index of which cache entry belongs to which song,
 * so a song that was streamed/cached can be listed and played offline.
 */
class CachedSongs(dir: File) {
    @Serializable
    data class Entry(val track: Track, val cacheKey: String, val url: String, val info: StreamInfo, val atMs: Long)

    private val file = JsonFile(File(dir, "cached-songs.json"), MapSerializer(String.serializer(), Entry.serializer()), holdWritesUntilRead = true)
    private val lock = com.ravi.musicspot.core.PlatformLock()
    @Volatile private var index: Map<String, Entry> = emptyMap()
    @Volatile private var loaded = false

    private val _complete = MutableStateFlow<List<Entry>>(emptyList())
    val complete: StateFlow<List<Entry>> = _complete

    init {
        IoScope.launch {
            val disk = try { file.read().orEmpty() } catch (e: Throwable) { emptyMap() }
            lock.withLock {
                index = disk + index
                loaded = true
            }
            _complete.value = index.values.sortedByDescending { it.atMs }
        }
    }

    /** Called once a stream is resolved for playback. Live radio, device files and downloads are skipped. */
    fun record(track: Track, r: Resolved) {
        if (Graph.incognito || track.source == SourceKind.RADIO || track.source == SourceKind.LOCAL || r.cacheKey.startsWith("file:") || !r.uri.startsWith("http")) return
        lock.withLock {
            index = (index - track.id) + (track.id to Entry(track, r.cacheKey, r.uri, r.info, currentTimeMillis()))
            if (index.size > MAX) index = index.values.sortedByDescending { it.atMs }.take(MAX).associateBy { it.track.id }
        }
        file.save { index }
        _complete.value = index.values.sortedByDescending { it.atMs }
    }

    /** The cached stream for [track] when available in the offline index; otherwise null. */
    fun offline(track: Track): Resolved? {
        val e = index[track.id] ?: return null
        return Resolved(e.url, e.info, emptyMap(), e.cacheKey)
    }

    fun isComplete(key: String): Boolean {
        if (key.startsWith("file:")) {
            val path = key.removePrefix("file://").removePrefix("file:")
            return PlatformIO.exists(path)
        }
        return index.values.any { it.cacheKey == key }
    }

    /** Recompute [complete], newest first. */
    fun refresh(): List<Entry> {
        val all = index.values.sortedByDescending { it.atMs }
        _complete.value = all
        return all
    }

    fun bytes(entries: List<Entry>): Long {
        var total = 0L
        for (e in entries) {
            if (e.cacheKey.startsWith("file:")) {
                val path = e.cacheKey.removePrefix("file://").removePrefix("file:")
                total += PlatformIO.fileSize(path)
            } else {
                total += (e.info.bitrateKbps ?: 192) * (e.track.durationMs / 8000L).coerceAtLeast(0L)
            }
        }
        return total
    }

    fun remove(trackId: String) {
        val e = index[trackId]
        lock.withLock {
            index = index - trackId
        }
        if (e != null && e.cacheKey.startsWith("file:")) {
            val path = e.cacheKey.removePrefix("file://").removePrefix("file:")
            PlatformIO.delete(path)
        }
        file.save { index }
        _complete.value = index.values.sortedByDescending { it.atMs }
    }

    fun clearIndex() {
        lock.withLock {
            index = emptyMap()
        }
        file.save { index }
        _complete.value = emptyList()
    }

    companion object {
        const val MAX = 2000
    }
}
