package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.source.unescapeHtml
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * JioSaavn lyrics provider:
 * Official synced and plain lyrics from JioSaavn's catalogue. Strong for Bollywood, Hindi, Punjabi, Tamil, Telugu.
 */
object JioSaavnLyrics {
    private const val SEARCH_URL = "https://www.jiosaavn.com/api.php?__call=search.getResults&p=1&n=5&_format=json&ctx=web6dot0"
    private const val LYRICS_URL = "https://www.jiosaavn.com/api.php?__call=lyrics.getLyrics&ctx=web6dot0&_format=json"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val query = "${cleaned.title} ${cleaned.artist}".encodeURLParameter()

        // 1. Search song on JioSaavn
        val searchResp = get("$SEARCH_URL&q=$query") ?: return null
        val results = (searchResp["results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (results.isEmpty()) return null

        // Find song with lyrics
        val hit = results.firstOrNull { s ->
            val hasLrc = (s["has_lyrics"] as? JsonPrimitive)?.contentOrNull == "true" ||
                ((s["more_info"] as? JsonObject)?.get("has_lyrics") as? JsonPrimitive)?.contentOrNull == "true"
            hasLrc && LyricsQuality.sameTitle(cleaned.title, (s["song"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        } ?: results.firstOrNull { (it["has_lyrics"] as? JsonPrimitive)?.contentOrNull == "true" } ?: return null

        val songId = (hit["id"] as? JsonPrimitive)?.contentOrNull ?: return null

        // 2. Fetch lyrics
        val lyricsResp = get("$LYRICS_URL&lyrics_id=$songId") ?: return null
        val rawLyrics = (lyricsResp["lyrics"] as? JsonPrimitive)?.contentOrNull ?: return null

        // Clean HTML entities and breaks
        val text = unescapeHtml(rawLyrics.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n"))
            .replace(Regex("""\n{3,}"""), "\n\n").trim()
        if (text.isEmpty()) return null

        // 3. Return synced if timestamped, else plain text
        return if (text.contains('[')) {
            val lines = LrcParser.parse(text, durationMs)
            if (lines.isNotEmpty()) Lyrics.Synced(lines, "JioSaavn", recordingMs = durationMs)
            else Lyrics.Plain(text, "JioSaavn")
        } else {
            Lyrics.Plain(text, "JioSaavn")
        }
    }

    private suspend fun get(url: String): JsonObject? {
        return runCatching {
            val body = Http.getString(url, mapOf("User-Agent" to "UpZe/1.0 (Mobile)"))
            json.parseToJsonElement(body) as? JsonObject
        }.getOrNull()
    }
}
