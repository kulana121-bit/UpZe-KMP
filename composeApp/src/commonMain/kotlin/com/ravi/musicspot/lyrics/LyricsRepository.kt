package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Crypto
import com.ravi.musicspot.core.File
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import io.ktor.http.encodeURLParameter

data class LyricsQuery(val title: String, val artist: String, val album: String?, val durationMs: Long, val videoId: String? = null)

interface LyricsProvider {
    val id: String
    val label: String
    suspend fun lookup(q: LyricsQuery): Lyrics?
}

object LrcLib : LyricsProvider {
    override val id = "lrclib"; override val label = "LRCLIB (synced)"
    private const val UA = "UpZe/2.0 (https://github.com/ ; GPL-3.0, based on OpenTune)"

    override suspend fun lookup(q: LyricsQuery): Lyrics? {
        val c = TrackNameCleaner.clean(q.title, q.artist)
        var get = "https://lrclib.net/api/get?track_name=${c.title.encodeURLParameter()}&artist_name=${c.artist.encodeURLParameter()}"
        if (!q.album.isNullOrBlank()) get += "&album_name=${q.album.encodeURLParameter()}"
        if (q.durationMs > 0) get += "&duration=${q.durationMs / 1000}"

        val direct = try {
            Http.json.parseToJsonElement(Http.getString(get, mapOf("User-Agent" to UA))) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        direct?.let { parse(it, q.durationMs)?.let { l -> return l } }

        val search = "https://lrclib.net/api/search?track_name=${c.title.encodeURLParameter()}&artist_name=${c.artist.encodeURLParameter()}"
        val arr = try {
            Http.json.parseToJsonElement(Http.getString(search, mapOf("User-Agent" to UA))) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return null

        val candidates = arr.mapNotNull { it as? JsonObject }.filter { o ->
            val d = ((o["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) * 1000
            (q.durationMs <= 0 || d <= 0 || kotlin.math.abs(d - q.durationMs) <= 5_000) &&
                LyricsQuality.sameTitle(c.title, (o["trackName"] as? JsonPrimitive)?.contentOrNull)
        }
        return candidates.sortedByDescending { (it["syncedLyrics"] as? JsonPrimitive)?.contentOrNull != null }
            .firstNotNullOfOrNull { parse(it, q.durationMs) }
    }

    fun parse(o: JsonObject, durationMs: Long): Lyrics? {
        if ((o["instrumental"] as? JsonPrimitive)?.contentOrNull == "true") return null
        (o["syncedLyrics"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { lrc ->
            val lines = LrcParser.parse(lrc, durationMs)
            if (lines.isNotEmpty()) return Lyrics.Synced(lines, "LRCLIB")
        }
        return (o["plainLyrics"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { Lyrics.Plain(it, "LRCLIB") }
    }
}

object LyricsPlusProvider : LyricsProvider {
    override val id = "lyricsplus"; override val label = "LyricsPlus (word-by-word)"
    override suspend fun lookup(q: LyricsQuery) = LyricsPlus.lookup(q.title, q.artist, q.durationMs, q.album)
}

object KuGouProvider : LyricsProvider {
    override val id = "kugou"; override val label = "KuGou (synced, strong for Asian music)"
    override suspend fun lookup(q: LyricsQuery) = KuGou.lookup(q.title, q.artist, q.durationMs)
}

object NetEaseProvider : LyricsProvider {
    override val id = "netease"; override val label = "NetEase Cloud Music (synced & word-by-word)"
    override suspend fun lookup(q: LyricsQuery) = NetEase.lookup(q.title, q.artist, q.durationMs)
}

object QQMusicProvider : LyricsProvider {
    override val id = "qqmusic"; override val label = "QQ Music (synced & QRC)"
    override suspend fun lookup(q: LyricsQuery) = QQMusic.lookup(q.title, q.artist, q.durationMs)
}

object MusixmatchProvider : LyricsProvider {
    override val id = "musixmatch"; override val label = "Musixmatch (word-by-word RichSync)"
    override suspend fun lookup(q: LyricsQuery) = Musixmatch.lookup(q.title, q.artist, q.durationMs)
}

object AmllProvider : LyricsProvider {
    override val id = "amll"; override val label = "AMLL (Apple Music syllable TTML)"
    override suspend fun lookup(q: LyricsQuery) = Amll.lookup(q.title, q.artist, q.durationMs)
}

object JioSaavnLyricsProvider : LyricsProvider {
    override val id = "jiosaavn_lyrics"; override val label = "JioSaavn Lyrics (Indian)"
    override suspend fun lookup(q: LyricsQuery) = JioSaavnLyrics.lookup(q.title, q.artist, q.durationMs)
}

object LyricsOvhProvider : LyricsProvider {
    override val id = "lyricsovh"; override val label = "Lyrics.ovh (plain)"
    override suspend fun lookup(q: LyricsQuery) = LyricsOvh.lookup(q.title, q.artist)
}

class YtLyricsProvider(private val fetch: suspend (String) -> Pair<String, String?>?) : LyricsProvider {
    override val id = "ytmusic"; override val label = "YouTube Music (plain)"
    override suspend fun lookup(q: LyricsQuery): Lyrics? {
        val vid = q.videoId ?: return null
        val (text, credit) = fetch(vid) ?: return null
        return Lyrics.Plain(text, credit?.let { "YouTube Music · $it" } ?: "YouTube Music")
    }
}

object LyricsChain {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = com.ravi.musicspot.core.PlatformLock()
    private class Shared(val track: String, val deferred: Deferred<Lyrics?>) { var waiters = 0 }
    private val inflight = HashMap<String, Shared>()
    @kotlin.concurrent.Volatile private var latestTrack: String? = null

    internal fun trackKey(q: LyricsQuery): String = q.videoId?.let { "v:$it" } ?: "${q.title}|${q.artist}|${q.durationMs / 2000}"

    suspend fun find(providers: List<LyricsProvider>, q: LyricsQuery, perProviderMs: Long = 8_000, onTried: (String, String) -> Unit = { _, _ -> }): Lyrics? {
        val track = trackKey(q)
        val key = track + "#" + providers.joinToString(",") { it.id } + "#" + perProviderMs
        val shared = lock.withLock {
            if (latestTrack != track) {
                latestTrack = track
                inflight.values.filter { it.track != track }.forEach { it.deferred.cancel(CancellationException("lyrics: track changed")) }
                val toRemove = inflight.entries.filter { it.value.track != track }.map { it.key }
                toRemove.forEach { inflight.remove(it) }
            }
            (inflight[key]?.takeIf { it.deferred.isActive } ?: Shared(track, scope.async { walk(providers, q, perProviderMs, onTried) }).also { inflight[key] = it })
                .also { it.waiters++ }
        }
        try {
            val r = shared.deferred.await()
            if (latestTrack != track) throw CancellationException("lyrics: stale result for a previous track")
            return r
        } finally {
            lock.withLock {
                shared.waiters--
                if (shared.waiters <= 0) {
                    if (shared.deferred.isActive) shared.deferred.cancel()
                    if (inflight[key] === shared) inflight.remove(key)
                }
            }
        }
    }

    private suspend fun walk(providers: List<LyricsProvider>, q: LyricsQuery, perProviderMs: Long, onTried: (String, String) -> Unit): Lyrics? =
        kotlinx.coroutines.coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL)
            val jobs = providers.map { p ->
                p to async {
                    gate.acquire()
                    try {
                        try { Answer(withTimeoutOrNull(perProviderMs) { p.lookup(q) }, null) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { Answer(null, "error: ${e.message}") }
                    } finally { gate.release() }
                }
            }
            var line: Lyrics? = null
            var plain: Lyrics? = null
            for ((p, job) in jobs) {
                val (r, err) = job.await()
                onTried(p.id, err ?: LyricsRank.label(r))
                when (LyricsRank.of(r)) {
                    LyricsRank.WORD -> { jobs.forEach { it.second.cancel() }; return@coroutineScope r }
                    LyricsRank.LINE -> if (line == null) line = r
                    LyricsRank.PLAIN -> if (plain == null) plain = r
                    LyricsRank.NONE -> {}
                }
            }
            line ?: plain
        }

    private const val PARALLEL = 4
    private data class Answer(val lyrics: Lyrics?, val error: String?)
}

enum class LyricsRank {
    WORD, LINE, PLAIN, NONE;
    companion object {
        fun of(r: Lyrics?): LyricsRank = when (r) {
            null -> NONE
            is Lyrics.Synced -> if (r.lines.any { it.wordSynced }) WORD else if (r.lines.isEmpty()) NONE else LINE
            is Lyrics.Plain -> PLAIN
        }
        fun label(r: Lyrics?): String = when (of(r)) { WORD -> "word-synced"; LINE -> "synced"; PLAIN -> "plain"; NONE -> "none" }
        fun best(answers: List<Lyrics?>): Lyrics? =
            answers.firstOrNull { of(it) == WORD } ?: answers.firstOrNull { of(it) == LINE } ?: answers.firstOrNull { of(it) == PLAIN }
    }
}

@Serializable
private data class CachedLyrics(val lyrics: Lyrics? = null, val atMs: Long)

class LyricsCache(private val dir: File, private val maxFiles: Int = 400) {
    private fun file(key: String): File {
        val hash = Crypto.sha1(key)
        return File(dir, "$hash.json")
    }

    fun sizeBytes(): Long = dir.listFiles().sumOf { it.length }

    fun get(key: String): Pair<Boolean, Lyrics?> {
        val f = file(key)
        if (!f.isFile) return false to null
        val c = runCatching { Http.json.decodeFromString(CachedLyrics.serializer(), f.readText()) }.getOrNull() ?: return false to null
        if (c.lyrics == null && currentTimeMillis() - c.atMs > 86_400_000L) return false to null
        return true to c.lyrics
    }

    fun put(key: String, lyrics: Lyrics?) {
        dir.mkdirs()
        runCatching {
            val f = file(key)
            val tmp = File(dir, "${f.name}.tmp")
            tmp.writeText(Http.json.encodeToString(CachedLyrics.serializer(), CachedLyrics(lyrics, currentTimeMillis())))
            tmp.renameTo(f)
        }
    }

    fun clear() { dir.listFiles().forEach { it.delete() } }
}

@Serializable
data class OfflineLyrics(val lyrics: Lyrics? = null, val roman: List<String?> = emptyList(), val atMs: Long = 0)

class OfflineLyricsStore(private val dir: File) {
    private fun base(trackId: String) = trackId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
    private fun json(trackId: String) = File(dir, base(trackId) + ".json")
    private fun lrc(trackId: String) = File(dir, base(trackId) + ".lrc")

    fun has(trackId: String): Boolean = json(trackId).isFile

    fun get(trackId: String): OfflineLyrics? {
        val f = json(trackId)
        if (!f.isFile) return null
        return runCatching { Http.json.decodeFromString(OfflineLyrics.serializer(), f.readText()) }.getOrNull()
    }

    fun put(trackId: String, lyrics: Lyrics?, roman: List<String?>) {
        dir.mkdirs()
        json(trackId).writeText(Http.json.encodeToString(OfflineLyrics.serializer(), OfflineLyrics(lyrics, roman, currentTimeMillis())))
        if (lyrics is Lyrics.Synced) lrc(trackId).writeText(LrcWriter.write(lyrics)) else lrc(trackId).delete()
    }

    fun delete(trackId: String) { json(trackId).delete(); lrc(trackId).delete() }
    fun clear() { dir.listFiles().forEach { it.delete() } }
}

object LrcWriter {
    private fun pad2(n: Long): String = if (n < 10) "0$n" else "$n"

    fun stamp(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val min = t / 60_000
        val sec = (t % 60_000) / 1000
        val cs = (t % 1000) / 10
        return "${pad2(min)}:${pad2(sec)}.${pad2(cs)}"
    }

    fun write(l: Lyrics.Synced): String = buildString {
        appendLine("[re:UpZe]")
        appendLine("[by:${l.source.replace(']', ')')}]")
        for (line in l.lines) {
            append('[').append(stamp(line.startMs)).append(']')
            if (line.wordSynced && line.words.isNotEmpty()) line.words.forEach { w -> append('<').append(stamp(w.startMs)).append('>').append(w.text) }
            else append(line.text)
            append('\n')
        }
    }
}
