/*
 * Ported from OpenTune 1.0.4 (lyrics/NetEase.kt) for UpZe KMP
 */
package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import io.ktor.http.encodeURLParameter

object NetEase {
    private const val BASE = "https://music.163.com/api"
    private const val TOLERANCE_MS = 5_000L

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val sParam = "${cleaned.title} ${cleaned.artist}".encodeURLParameter()
        val searchUrl = "$BASE/search/get?s=$sParam&type=1&limit=10"
        val search = get(searchUrl)
        val song = best(search, cleaned.title, durationMs, cleaned.artist) ?: return null
        val id = (song["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val recordingMs = (song["duration"] as? JsonPrimitive)?.longOrNull ?: 0L
        val lyricResp = get("$BASE/song/lyric?id=$id&lv=1&kv=1&tv=1&yv=1")

        // 1. Try word-by-word YRC format first
        val yrcText = (((lyricResp as? JsonObject)?.get("yrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
        if (!yrcText.isNullOrBlank()) {
            val yrcLines = KuGou.clean(YrcParser.parse(yrcText, durationMs), cleaned.title, cleaned.artist)
            if (yrcLines.isNotEmpty()) {
                return Lyrics.Synced(yrcLines, "NetEase", recordingMs = recordingMs)
            }
        }

        // 2. Fall back to standard line-synced LRC
        val lrc = lrcOf(lyricResp) ?: return null
        val lines = KuGou.clean(LrcParser.parse(lrc, durationMs), cleaned.title, cleaned.artist)
        return if (lines.isEmpty()) null else Lyrics.Synced(lines, "NetEase", recordingMs = recordingMs)
    }

    private val VERSION = Regex("""\b(remix|live|instrumental|karaoke|acoustic|cover|sped up|slowed|nightcore|8d)\b|伴奏|翻唱|现场""", RegexOption.IGNORE_CASE)

    internal fun pick(search: JsonElement?, title: String, durationMs: Long, artist: String = ""): Long? =
        best(search, title, durationMs, artist)?.let { (it["id"] as? JsonPrimitive)?.longOrNull }

    internal fun best(search: JsonElement?, title: String, durationMs: Long, artist: String = ""): JsonObject? {
        val songs = (((search as? JsonObject)?.get("result") as? JsonObject)?.get("songs") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val want = key(title)
        val wantsVersion = VERSION.containsMatchIn(title)
        fun gap(o: JsonObject) = if (durationMs <= 0) 0L else abs(((o["duration"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE / 2) - durationMs)
        fun artists(o: JsonObject) = (o["artists"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
        val fitting = songs
            .filter { o -> val name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(); key(name) == want || key(name).startsWith(want) }
            .filter { wantsVersion || !VERSION.containsMatchIn((it["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            .filter { gap(it) <= TOLERANCE_MS }
        val byArtist = if (artist.isBlank()) emptyList() else fitting.filter { o -> artists(o).any { LyricsQuality.sameArtist(artist, it) && LyricsQuality.sameArtist(it, artist) } }
        return byArtist.ifEmpty { fitting }.minByOrNull(::gap)
    }

    internal fun lrcOf(answer: JsonElement?): String? =
        (((answer as? JsonObject)?.get("lrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.contains('[') && it.lines().count { l -> l.contains(']') && l.substringAfter(']').isNotBlank() } >= 3 }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private suspend fun get(url: String): JsonElement? {
        return runCatching {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
                "Referer" to "https://music.163.com/"
            )
            val body = Http.getString(url, headers)
            json.parseToJsonElement(body)
        }.getOrNull()
    }
}
