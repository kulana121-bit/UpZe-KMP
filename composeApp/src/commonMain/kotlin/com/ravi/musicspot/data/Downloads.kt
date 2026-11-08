package com.ravi.musicspot.data

import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.currentTimeMillis
import com.ravi.musicspot.core.File
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.core.JsonFile
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.Resolved
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
data class DownloadEntry(
    val track: Track,
    val state: State = State.QUEUED,
    val bytes: Long = 0,
    val total: Long = 0,
    val path: String? = null,
    val error: String? = null,
    val info: StreamInfo? = null,
    val atMs: Long = currentTimeMillis(),
) {
    enum class State { QUEUED, RUNNING, DONE, FAILED }
    val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

@Serializable
data class DownloadIndex(val items: Map<String, DownloadEntry> = emptyMap())

class DownloadsRepo(val dir: File) {
    private val file = JsonFile(File(dir, "downloads.json"), DownloadIndex.serializer(), debounceMs = 1000)
    private val _items = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())
    val items: StateFlow<Map<String, DownloadEntry>> = _items
    private val activeJobs = mutableMapOf<String, Job>()

    fun load() {
        val loaded = file.read()?.items.orEmpty()
        _items.value = loaded.mapValues { (_, e) ->
            when {
                e.state == DownloadEntry.State.DONE && e.path?.let { File(it).isFile } != true ->
                    e.copy(state = DownloadEntry.State.FAILED, error = "File missing")
                e.state == DownloadEntry.State.RUNNING ->
                    e.copy(state = DownloadEntry.State.QUEUED)
                else -> e
            }
        }
    }

    private fun edit(f: (Map<String, DownloadEntry>) -> Map<String, DownloadEntry>) {
        _items.update(f)
        file.save { DownloadIndex(_items.value) }
    }

    fun update(id: String, f: (DownloadEntry) -> DownloadEntry) = edit { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    fun isDownloaded(id: String) = _items.value[id]?.state == DownloadEntry.State.DONE

    fun resolvedFor(id: String): Resolved? {
        val e = _items.value[id] ?: return null
        if (e.state != DownloadEntry.State.DONE) return null
        val f = File(e.path ?: return null).takeIf { it.isFile } ?: return null
        val info = (e.info ?: StreamInfo(e.track.source)).let { it.copy(codec = it.codec, sourceTrackId = id) }
        return Resolved(f.path, info, cacheKey = "file:$id")
    }

    fun enqueue(t: Track, wifiOnly: Boolean) {
        if (_items.value[t.id]?.state == DownloadEntry.State.DONE) return
        edit { it + (t.id to DownloadEntry(t)) }

        val job = IoScope.launch {
            try {
                update(t.id) { it.copy(state = DownloadEntry.State.RUNNING, error = null) }
                val settings = Graph.settings.value
                val r = Graph.sources.resolve(t, settings.downloadQuality, metered = false)
                if (!r.uri.startsWith("http")) throw Exception("This source can't be downloaded (${r.info.source.label})")

                val ext = when (r.info.codec?.lowercase()) {
                    "opus" -> "webm"
                    "aac" -> "m4a"
                    "flac" -> "flac"
                    "mp3" -> "mp3"
                    else -> "audio"
                }
                dir.mkdirs()
                val part = File(dir, safe(t.id) + ".part")
                val out = File(dir, safe(t.id) + "." + ext)

                fetchRanged(r, part) { done, total ->
                    update(t.id) { it.copy(bytes = done, total = total) }
                }

                if (part.length < 1024L) throw Exception("Download was empty")
                if (!part.renameTo(out)) throw Exception("Couldn't save the file")

                update(t.id) {
                    it.copy(
                        state = DownloadEntry.State.DONE,
                        path = out.absolutePath,
                        bytes = out.length,
                        total = out.length,
                        info = r.info
                    )
                }

                // 1.0.6 offline lyrics: save the song's synced lyrics (and romanization) with the download. Best effort.
                try {
                    withTimeoutOrNull(30_000L) {
                        Graph.saveOfflineLyrics(t)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            } catch (e: CancellationException) {
                update(t.id) { it.copy(state = DownloadEntry.State.QUEUED) }
                throw e
            } catch (e: Exception) {
                update(t.id) { it.copy(state = DownloadEntry.State.FAILED, error = e.message ?: e::class.simpleName) }
            } finally {
                activeJobs.remove(t.id)
            }
        }
        activeJobs[t.id] = job
    }

    private suspend fun fetchRanged(r: Resolved, part: File, progress: suspend (Long, Long) -> Unit) {
        val CHUNK = 1048576L // 1 MiB chunks
        var offset = if (part.isFile) part.length else 0L
        var total = -1L

        while (total < 0L || offset < total) {
            val end = offset + CHUNK - 1L
            val response = Http.client.get(r.uri) {
                header(HttpHeaders.Range, "bytes=$offset-$end")
                r.headers.forEach { (k, v) -> header(k, v) }
            }

            val status = response.status.value
            if (status == 416) {
                total = offset
                break
            }
            if (status !in 200..299) {
                throw Exception("HTTP $status")
            }

            // Server ignored Range on resume: start over
            if (status == 200 && offset > 0L) {
                part.delete()
                offset = 0L
            }

            if (status == 206) {
                val contentRange = response.headers[HttpHeaders.ContentRange]
                val rangeStart = contentRange?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                if (rangeStart != null && rangeStart != offset) {
                    throw Exception("Content-Range starts at $rangeStart, expected $offset")
                }
            }

            val contentRange = response.headers[HttpHeaders.ContentRange]
            total = contentRange?.substringAfter('/')?.toLongOrNull()
                ?: (if (status == 200) response.contentLength()?.let { it + offset } else null)
                ?: total

            val bytes = response.body<ByteArray>()
            if (bytes.isNotEmpty()) {
                part.appendBytes(bytes)
                offset += bytes.size
            } else {
                total = offset
                break
            }

            if (status == 200) {
                total = offset
            }

            progress(offset, total)
        }
    }

    fun cancel(id: String) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        delete(id)
    }

    fun retry(id: String, wifiOnly: Boolean) {
        _items.value[id]?.let {
            edit { m -> m - id }
            enqueue(it.track, wifiOnly)
        }
    }

    fun delete(id: String) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        _items.value[id]?.path?.let { File(it).delete() }
        runCatching { Graph.offlineLyrics.delete(id) }
        File(dir, safe(id) + ".part").delete()
        edit { it - id }
    }

    fun deleteAll() {
        _items.value.keys.toList().forEach { cancel(it) }
    }

    fun usedBytes(): Long = dir.listFiles().filter { it.isFile }.sumOf { it.length }

    companion object {
        const val KEY_ID = "id"
        const val TAG = "musicspot-download"
        fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
    }
}
