/*
 * Ported from OpenTune 1.0.4 (lyrics/KuGou.kt) for UpZe KMP
 */
package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import kotlin.math.abs
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import io.ktor.http.encodeURLParameter

object KuGou {
    private const val BASE = "https://lyrics.kugou.com"
    private const val TOLERANCE_MS = 8_000.0
    private const val TITLE_ONLY_TOLERANCE_MS = 2_000.0

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        suspend fun search(keyword: String): JsonElement? {
            var url = "$BASE/search?ver=1&man=yes&client=mobi&keyword=${keyword.encodeURLParameter()}"
            if (durationMs > 0) url += "&duration=$durationMs"
            return get(url)
        }
        val hit = best(search("${cleaned.artist} - ${cleaned.title}"), durationMs, cleaned.title)
            ?: durationMs.takeIf { it > 0 }?.let { best(search(cleaned.title), it, cleaned.title, TITLE_ONLY_TOLERANCE_MS) }
            ?: return null
        val id = hit.str("id") ?: return null
        val key = hit.str("accesskey") ?: return null

        // 1. Try word-by-word KRC format first
        @OptIn(ExperimentalEncodingApi::class)
        val krcRaw = (runCatching { get("$BASE/download?ver=1&client=pc&id=$id&accesskey=$key&fmt=krc&charset=utf8") }.getOrNull() as? JsonObject)?.str("content")?.takeIf { it.isNotBlank() }
        if (krcRaw != null) {
            @OptIn(ExperimentalEncodingApi::class)
            val bytes = runCatching { Base64.decode(krcRaw) }.getOrNull()
            if (bytes != null) {
                val decrypted = KrcDecryptor.decrypt(bytes)
                if (!decrypted.isNullOrBlank()) {
                    val krcLines = clean(KrcParser.parse(decrypted, durationMs), cleaned.title, cleaned.artist)
                    if (krcLines.isNotEmpty()) {
                        return Lyrics.Synced(krcLines, "KuGou", recordingMs = hit.num("duration")?.toLong() ?: 0)
                    }
                }
            }
        }

        // 2. Fall back to standard line-synced LRC
        val download = "$BASE/download?ver=1&client=pc&id=$id&accesskey=$key&fmt=lrc&charset=utf8"
        val lrc = lrcOf(get(download)) ?: return null
        val lines = clean(LrcParser.parse(lrc, durationMs), cleaned.title, cleaned.artist)
        return if (lines.isEmpty()) null else Lyrics.Synced(lines, "KuGou", recordingMs = hit.num("duration")?.toLong() ?: 0)
    }

    private val VERSION = Regex("""\b(remix|live|instrumental|karaoke|acoustic|cover|sped up|slowed|dj)\b|伴奏|翻唱|现场""", RegexOption.IGNORE_CASE)

    internal fun pick(search: JsonElement?, durationMs: Long, title: String = "", tolerance: Double = TOLERANCE_MS): Pair<String, String>? {
        val best = best(search, durationMs, title, tolerance) ?: return null
        val id = best.str("id") ?: return null
        val key = best.str("accesskey") ?: return null
        return id to key
    }

    private fun best(search: JsonElement?, durationMs: Long, title: String, tolerance: Double = TOLERANCE_MS): JsonObject? {
        val candidates = ((search as? JsonObject)?.get("candidates") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        fun gap(o: JsonObject) = if (durationMs <= 0) 0.0 else abs((o.num("duration") ?: Double.MAX_VALUE) - durationMs)
        val wantsVersion = VERSION.containsMatchIn(title)
        return candidates
            .filter { gap(it) <= tolerance }
            .filter { wantsVersion || !VERSION.containsMatchIn(it.str("song").orEmpty()) }
            .filter { title.isBlank() || LyricsQuality.sameTitle(title, it.str("song")) }
            .minByOrNull(::gap)
    }

    @OptIn(ExperimentalEncodingApi::class)
    internal fun lrcOf(download: JsonElement?): String? {
        val content = (download as? JsonObject)?.str("content")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Base64.decode(content).decodeToString() }.getOrNull()
    }

    private val CREDIT = Regex(
        """^\s*(作词|作曲|编曲|制作人|监制|混音|和声|吉他|贝斯|鼓|录音|母带|出品|发行|OP|SP|(lyrics|words|music|composed|composer|written|arranged|produced|producer|mixed|mastered|recorded|engineered|vocals?)( by| at)?)\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )

    internal fun clean(lines: List<LyricLine>, title: String, artist: String): List<LyricLine> {
        val t = title.lowercase()
        val a = artist.lowercase()
        fun isHeader(text: String) = text.contains(" - ") && text.lowercase().let { it.contains(t) || it.contains(a) }
        fun isCredit(text: String) = CREDIT.containsMatchIn(text) || text.contains('：')
        val firstLyric = lines.indexOfFirst { l -> l.text.isNotBlank() && !isHeader(l.text) && !isCredit(l.text) }
        if (firstLyric < 0) return emptyList()
        return lines.drop(firstLyric).filterNot { CREDIT.containsMatchIn(it.text) }
    }

    private suspend fun get(url: String): JsonElement? {
        return runCatching {
            val body = Http.getString(url, mapOf("User-Agent" to "UpZe"))
            json.parseToJsonElement(body)
        }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
