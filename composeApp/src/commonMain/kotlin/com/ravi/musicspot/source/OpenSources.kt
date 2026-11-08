package com.ravi.musicspot.source

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import io.ktor.http.encodeURLParameter

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.o(k: String) = this[k] as? JsonObject
private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

class AudiusSource(private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.AUDIUS
    override fun available() = enabled()
    @Volatile private var host: String? = null

    private suspend fun host(): String = host ?: run {
        val h = runCatching {
            val o = Http.json.parseToJsonElement(Http.getString("https://api.audius.co")) as? JsonObject
            (o?.get("data") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.firstOrNull { it.startsWith("https://") }
        }.getOrNull() ?: "https://api.audius.co"
        h.also { host = it }
    }

    override suspend fun search(query: String, limit: Int): List<Track> {
        val url = "${host()}/v1/tracks/search?query=${query.encodeURLParameter()}&app_name=$APP&limit=$limit"
        val o = Http.json.parseToJsonElement(Http.getString(url)) as? JsonObject ?: return emptyList()
        return (o["data"] as? JsonArray).orEmpty().mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            val id = t.s("id") ?: return@mapNotNull null
            if (t.s("is_streamable") == "false") return@mapNotNull null
            Track("au:$id", t.s("title").orEmpty(), t.o("user")?.s("name").orEmpty(), null, (t.l("duration") ?: 0) * 1000,
                t.o("artwork")?.s("480x480") ?: t.o("artwork")?.s("150x150"), format = "mp3")
        }
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val url = "${host()}/v1/tracks/${track.nativeId}/stream?app_name=$APP"
        return Resolved(url, StreamInfo(kind, "MP3", null, null, track.id), cacheKey = "au:${track.nativeId}")
    }

    override suspend fun probe() = "${search("lofi", 2).size} results from ${host()}"
    companion object { const val APP = "UpZe" }
}

class JamendoSource(private val clientId: () -> String?, private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.JAMENDO
    override fun available() = enabled() && !clientId().isNullOrBlank()

    private fun base(): String {
        val cid = clientId() ?: throw SourceUnavailableException("Jamendo: add your client_id in Settings → Sources")
        return "https://api.jamendo.com/v3.0/tracks/?client_id=$cid&format=json&audioformat=mp32&audiodlformat=flac"
    }

    override suspend fun search(query: String, limit: Int): List<Track> {
        val url = "${base()}&search=${query.encodeURLParameter()}&limit=$limit"
        val o = Http.json.parseToJsonElement(Http.getString(url)) as? JsonObject ?: return emptyList()
        return (o["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            val flac = t.s("audiodownload_allowed") == "true"
            Track("jm:${t.s("id") ?: return@mapNotNull null}", t.s("name").orEmpty(), t.s("artist_name").orEmpty(), t.s("album_name"),
                (t.l("duration") ?: 0) * 1000, t.s("image"), format = if (flac) "flac" else "mp3")
        }
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val url = "${base()}&id=${track.nativeId}"
        val o = Http.json.parseToJsonElement(Http.getString(url)) as? JsonObject
        val t = (o?.get("results") as? JsonArray)?.firstOrNull() as? JsonObject ?: throw SourceUnavailableException("Jamendo: not found")
        val wantFlac = (quality == Quality.LOSSLESS || quality == Quality.MAX) && t.s("audiodownload_allowed") == "true"
        val mediaUrl = (if (wantFlac) t.s("audiodownload") else t.s("audio"))?.replace("http://", "https://") ?: throw SourceUnavailableException("Jamendo: no audio")
        return Resolved(mediaUrl, StreamInfo(kind, if (wantFlac) "FLAC" else "MP3", null, null, track.id), cacheKey = "jm:${track.nativeId}@${if (wantFlac) "flac" else "mp3"}")
    }

    override suspend fun probe() = "${search("piano", 2).size} results"
}

class ArchiveSource(private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.ARCHIVE
    override fun available() = enabled()

    suspend fun searchItems(query: String, rows: Int = 20): List<Collection> {
        val url = "https://archive.org/advancedsearch.php?q=(${query.encodeURLParameter()})+AND+mediatype:audio&fl[]=identifier&fl[]=title&fl[]=creator&rows=$rows&output=json"
        val o = Http.json.parseToJsonElement(Http.getString(url)) as? JsonObject ?: return emptyList()
        val docs = (o.o("response")?.get("docs") as? JsonArray).orEmpty()
        return docs.mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val id = d.s("identifier") ?: return@mapNotNull null
            val creator = (d["creator"] as? JsonPrimitive)?.contentOrNull ?: (d["creator"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            Collection("ia:$id", Collection.Kind.ALBUM, d.s("title") ?: id, creator, "https://archive.org/services/img/$id")
        }
    }

    suspend fun item(identifier: String): Pair<Collection, List<Track>> {
        val o = Http.json.parseToJsonElement(Http.getString("https://archive.org/metadata/$identifier")) as? JsonObject
            ?: throw SourceUnavailableException("Archive: item not found")
        val meta = o.o("metadata")
        val title = (meta?.get("title") as? JsonPrimitive)?.contentOrNull ?: identifier
        val creator = (meta?.get("creator") as? JsonPrimitive)?.contentOrNull ?: ""
        val files = (o["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val flac = files.filter { (it.s("format") ?: "").contains("Flac", true) }
        val chosen = flac.ifEmpty { files.filter { it.s("format") == "VBR MP3" } }
        val art = "https://archive.org/services/img/$identifier"
        val tracks = chosen.sortedBy { it.s("track")?.substringBefore('/')?.toIntOrNull() ?: Int.MAX_VALUE }.map { f ->
            val name = f.s("name").orEmpty()
            Track("ia:$identifier/$name", f.s("title") ?: name.substringBeforeLast('.'), f.s("creator") ?: creator, title,
                (f.s("length")?.let { l -> l.toDoubleOrNull()?.times(1000)?.toLong() ?: parseClock(l) } ?: 0), art,
                albumId = "ia:$identifier", format = if ((f.s("format") ?: "").contains("Flac", true)) "flac" else "mp3")
        }
        return Collection("ia:$identifier", Collection.Kind.ALBUM, title, creator, art) to tracks
    }

    override suspend fun search(query: String, limit: Int): List<Track> = emptyList()

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val path = track.nativeId.split('/').joinToString("/") { it.encodeURLParameter() }
        return Resolved("https://archive.org/download/$path", StreamInfo(kind, track.format?.uppercase(), null, null, track.id), cacheKey = "ia:${track.nativeId}")
    }

    override suspend fun probe() = "${searchItems("jazz", 2).size} items"

    private fun parseClock(s: String): Long {
        val parts = s.split(':').mapNotNull { it.toDoubleOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            else -> 0
        }.toLong()
    }
}
