package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Lyrics.ovh provider:
 * Public, keyless API for plain text song lyrics.
 */
object LyricsOvh {
    private const val BASE = "https://api.lyrics.ovh/v1"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(title: String, artist: String): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val encArtist = cleaned.artist.encodeURLParameter()
        val encTitle = cleaned.title.encodeURLParameter()
        val url = "$BASE/$encArtist/$encTitle"

        val text = runCatching {
            val body = Http.getString(url, mapOf("User-Agent" to "UpZe/1.0"))
            val obj = json.parseToJsonElement(body) as? JsonObject
            (obj?.get("lyrics") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()?.trim()

        if (text.isNullOrBlank()) return null
        return Lyrics.Plain(text, "lyrics.ovh")
    }
}
