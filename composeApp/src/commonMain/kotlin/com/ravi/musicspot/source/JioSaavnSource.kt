package com.ravi.musicspot.source

import com.ravi.musicspot.core.Crypto
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.PlaybackLog
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.Shelf
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class JioSaavnSource(private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.JIOSAAVN
    override fun available() = enabled()

    private suspend fun call(
        method: String,
        vararg params: Pair<String, String>,
        ctx: String = "android",
        log: Boolean = false
    ): JsonElement {
        val url = buildString {
            append(API).append("?__call=").append(method)
            append("&_format=json&_marker=0&ctx=").append(ctx).append("&api_version=4")
            params.forEach { (k, v) ->
                append("&").append(k.encodeURLParameter()).append("=").append(v.encodeURLParameter())
            }
        }
        val body = try {
            Http.getString(url, mapOf("User-Agent" to Http.BROWSER_UA))
        } catch (e: Exception) {
            if (log) PlaybackLog.log("JioSaavn $method: ${e.message}")
            throw e
        }
        return try {
            Http.json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw SourceUnavailableException("JioSaavn $method: invalid JSON", e)
        }
    }

    override suspend fun search(query: String, limit: Int): List<Track> = coroutineScope {
        val primary = async { runCatching { searchResults(query, limit) } }
        val early = withTimeoutOrNull(FALLBACK_AFTER_MS) { primary.await() }
        if (early != null && early.getOrNull()?.isNotEmpty() == true) return@coroutineScope early.getOrThrow()
        val fallback = async { runCatching { autocomplete(query, limit) } }
        val r1 = primary.await().getOrNull()
        if (!r1.isNullOrEmpty()) return@coroutineScope r1
        val r2 = fallback.await().getOrNull()
        if (!r2.isNullOrEmpty()) return@coroutineScope r2
        emptyList()
    }

    private suspend fun searchResults(query: String, limit: Int): List<Track> {
        val o = call("search.getResults", "q" to query, "n" to limit.toString(), log = true) as? JsonObject
            ?: return emptyList()
        val results = (o["results"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return results.mapNotNull(::toTrack)
    }

    private suspend fun autocomplete(query: String, limit: Int): List<Track> {
        val o = call("autocomplete.get", "query" to query, ctx = "web", log = true) as? JsonObject
            ?: return emptyList()
        val songs = o.obj("songs")
        val data = (songs?.get("data") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val pids = data.mapNotNull { it.str("id") }.take(limit)
        if (pids.isEmpty()) return emptyList()
        val details = call("song.getDetails", "pids" to pids.joinToString(",")) as? JsonObject
            ?: return emptyList()
        return pids.mapNotNull { pid -> (details[pid] as? JsonObject)?.let(::toTrack) }
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val id = track.nativeId
        val details = call("song.getDetails", "pids" to id) as? JsonObject
            ?: throw SourceUnavailableException("JioSaavn song $id not found")
        val song = (details[id] as? JsonObject)
            ?: throw SourceUnavailableException("JioSaavn song $id details missing")
        val info = song.obj("more_info") ?: throw SourceUnavailableException("JioSaavn song $id metadata missing")
        val encrypted = info.str("encrypted_media_url")
            ?: throw SourceUnavailableException("JioSaavn song $id: no media URL")

        val decoded = decodeMediaUrl(encrypted)
        val has320 = info.str("320kbps").equals("true", true)
        val kbps = if (has320) 320 else 160
        val url = rendition(decoded, kbps)
            ?: throw SourceUnavailableException("JioSaavn: unexpected media URL")

        return Resolved(
            uri = url,
            info = StreamInfo(kind, "AAC", kbps, 44_100, track.id),
            headers = mapOf("User-Agent" to Http.BROWSER_UA),
            cacheKey = "js:$id@$kbps"
        )
    }

    override suspend fun probe(): String = "${search("arijit", 3).size} results"

    suspend fun charts(): Shelf {
        val arr = call("content.getCharts") as? JsonArray ?: return Shelf("JioSaavn charts")
        return Shelf("JioSaavn charts", collections = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Collection(
                "js:pl:" + (o.str("id") ?: return@mapNotNull null),
                Collection.Kind.PLAYLIST,
                unescapeHtml(o.str("title")),
                "JioSaavn",
                hiRes(o.str("image"))
            )
        })
    }

    data class Launch(val trending: Shelf, val newReleases: Shelf, val playlists: Shelf, val charts: Shelf)

    suspend fun launch(): Launch {
        val o = call("webapi.getLaunchData") as? JsonObject
            ?: throw SourceUnavailableException("JioSaavn: no launch data")
        fun shelf(key: String, title: String): Shelf {
            val arr = (o[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            return Shelf(
                title,
                tracks = arr.mapNotNull { if (it.str("type") == "song") toTrack(it) else null },
                collections = arr.mapNotNull(::toCollection),
                subtitle = "JioSaavn"
            )
        }
        return Launch(
            shelf("new_trending", "Trending on JioSaavn"),
            shelf("new_albums", "New releases"),
            shelf("top_playlists", "Top playlists"),
            shelf("charts", "JioSaavn charts")
        )
    }

    suspend fun trending(): Shelf = launch().trending

    internal fun toCollection(o: JsonObject): Collection? {
        val id = o.str("id")?.takeIf { ID.matches(it) } ?: return null
        val kind = when (o.str("type")) {
            "album" -> Collection.Kind.ALBUM
            "playlist" -> Collection.Kind.PLAYLIST
            else -> return null
        }
        val sub = o.str("subtitle")?.takeIf { it.isNotBlank() }?.let(::unescapeHtml)
            ?: (o.obj("more_info")?.obj("artistMap")?.get("primary_artists") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.str("name") }
                .joinToString(", ")
                .ifBlank { null }
                ?.let(::unescapeHtml)
        return Collection(
            (if (kind == Collection.Kind.ALBUM) "js:al:" else "js:pl:") + id,
            kind,
            unescapeHtml(o.str("title")),
            sub ?: "JioSaavn",
            hiRes(o.str("image"))
        )
    }

    suspend fun album(id: String): Pair<Collection, List<Track>> {
        val o = call("content.getAlbumDetails", "albumid" to id) as? JsonObject
            ?: throw SourceUnavailableException("JioSaavn album")
        val tracks = (o["list"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toTrack) }
        return Collection(
            "js:al:$id",
            Collection.Kind.ALBUM,
            unescapeHtml(o.str("title")),
            unescapeHtml(o.str("subtitle")),
            hiRes(o.str("image"))
        ) to tracks
    }

    suspend fun playlist(id: String): Pair<Collection, List<Track>> {
        val o = call("playlist.getDetails", "listid" to id, "n" to "100") as? JsonObject
            ?: throw SourceUnavailableException("JioSaavn playlist")
        val tracks = (o["list"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toTrack) }
        return Collection(
            "js:pl:$id",
            Collection.Kind.PLAYLIST,
            unescapeHtml(o.str("title")),
            unescapeHtml(o.str("subtitle")),
            hiRes(o.str("image"))
        ) to tracks
    }

    internal fun toTrack(o: JsonObject): Track? {
        val id = o.str("id")?.takeIf { ID.matches(it) } ?: return null
        if (o.str("type") != null && o.str("type") != "song") return null
        val info = o.obj("more_info")
        val primary = (info?.obj("artistMap")?.get("primary_artists") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("name") }
            .map(::unescapeHtml)
        return Track(
            id = "js:$id",
            title = unescapeHtml(o.str("title")),
            artist = primary.joinToString(", ").ifBlank { unescapeHtml(o.str("subtitle")?.substringBefore(" - ")) },
            album = info?.str("album")?.let(::unescapeHtml),
            durationMs = (info?.str("duration")?.toLongOrNull() ?: 0) * 1000,
            artUrl = hiRes(o.str("image")),
            albumId = info?.str("album_id")?.let { "js:al:$it" },
            explicit = o.str("explicit_content") == "1",
            year = o.str("year"),
            bitrateKbps = if (info?.str("320kbps").equals("true", true)) 320 else 160,
        )
    }

    companion object {
        private const val API = "https://www.jiosaavn.com/api.php"
        const val FALLBACK_AFTER_MS = 4_000L
        private val ID = Regex("[A-Za-z0-9_-]+")
        private val RENDITION = Regex("_(12|48|96|160|320)\\.mp4$", RegexOption.IGNORE_CASE)

        fun hiRes(url: String?): String? = url?.replace("http://", "https://")?.replace(Regex("(\\d+)x(\\d+)"), "500x500")

        /** DES-ECB with JioSaavn's public key using cross-platform Crypto */
        fun decodeMediaUrl(enc: String): String = Crypto.decryptDesEcb(enc.trim(), "38346591")

        fun rendition(decoded: String, kbps: Int): String? {
            val url = decoded.replace(Regex("^http://"), "https://")
            val parsed = runCatching { Url(url) }.getOrNull() ?: return null
            if (parsed.protocol != URLProtocol.HTTPS) return null
            if (parsed.host != "saavncdn.com" && !parsed.host.endsWith(".saavncdn.com")) return null
            if (!RENDITION.containsMatchIn(parsed.encodedPath)) return null
            return url.replace(RENDITION, "_$kbps.mp4")
        }

        private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    }
}
