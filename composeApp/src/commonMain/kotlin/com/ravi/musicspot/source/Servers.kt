package com.ravi.musicspot.source

import com.ravi.musicspot.account.Account
import com.ravi.musicspot.core.Crypto
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import io.ktor.http.encodeURLParameter
import kotlin.random.Random

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

fun serverBase(input: String): String {
    val t = input.trim().trimEnd('/')
    require(!t.startsWith("http://", true)) { "Only https:// servers are supported" }
    val url = if (t.startsWith("https://", true)) t else "https://$t"
    return url
}

class SubsonicSource(private val account: () -> Account?, private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.SUBSONIC
    override fun available() = enabled() && account() != null

    private fun url(method: String, acct: Account = account() ?: throw SourceUnavailableException("No Subsonic server"), vararg params: Pair<String, String>): String {
        val saltBytes = Random.nextBytes(8)
        val salt = saltBytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val token = Crypto.md5(acct.secret + salt)
        var base = "${serverBase(acct.extra ?: "")}/rest/$method?u=${acct.name?.encodeURLParameter()}&t=$token&s=$salt&v=1.16.1&c=UpZe&f=json"
        params.forEach { (k, v) -> base += "&$k=${v.encodeURLParameter()}" }
        return base
    }

    private suspend fun call(method: String, vararg params: Pair<String, String>, acct: Account? = null): JsonObject {
        val a = acct ?: account() ?: throw SourceUnavailableException("No Subsonic server")
        val o = Http.json.parseToJsonElement(Http.getString(url(method, a, *params))) as JsonObject
        val r = o["subsonic-response"] as? JsonObject ?: throw SourceUnavailableException("Not a Subsonic server")
        if (r.s("status") != "ok") throw SourceUnavailableException("Subsonic: " + ((r["error"] as? JsonObject)?.s("message") ?: "error"))
        return r
    }

    suspend fun test(acct: Account): String { call("ping", acct = acct); return "OK" }

    override suspend fun search(query: String, limit: Int): List<Track> {
        val r = call("search3", "query" to query, "songCount" to limit.toString(), "albumCount" to "0", "artistCount" to "0")
        val songs = ((r["searchResult3"] as? JsonObject)?.get("song") as? JsonArray).orEmpty()
        return songs.mapNotNull { (it as? JsonObject)?.let(::toTrack) }
    }

    suspend fun randomSongs(n: Int = 30): List<Track> =
        ((call("getRandomSongs", "size" to n.toString())["randomSongs"] as? JsonObject)?.get("song") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toTrack) }

    private fun toTrack(o: JsonObject): Track? {
        val id = o.s("id") ?: return null
        return Track("sub:$id", o.s("title").orEmpty(), o.s("artist").orEmpty(), o.s("album"), (o.l("duration") ?: 0) * 1000,
            o.s("coverArt")?.let { runCatching { url("getCoverArt", params = *arrayOf("id" to it, "size" to "600")) }.getOrNull() },
            year = o.s("year"), format = o.s("suffix"), bitrateKbps = o.l("bitRate")?.toInt())
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val lossless = quality == Quality.LOSSLESS || (quality == Quality.MAX && !metered)
        val params = if (lossless) arrayOf("id" to track.nativeId, "format" to "raw") else arrayOf("id" to track.nativeId, "maxBitRate" to "320")
        val u = url("stream", params = *params)
        val codec = if (lossless) track.format?.uppercase() else "MP3"
        return Resolved(u, StreamInfo(kind, codec, if (lossless) track.bitrateKbps else 320.coerceAtMost(track.bitrateKbps ?: 320), null, track.id),
            cacheKey = "sub:${track.nativeId}@${if (lossless) "raw" else "320"}")
    }

    override suspend fun probe() = "ping ${test(account() ?: throw SourceUnavailableException("not configured"))}"
}

class JellyfinSource(private val account: () -> Account?, private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.JELLYFIN
    override fun available() = enabled() && account() != null

    private fun authHeader(acct: Account): String =
        "MediaBrowser Client=\"UpZe\", Device=\"KMP\", DeviceId=\"upze-app\", Version=\"2.0\", Token=\"${acct.secret}\""

    private fun baseOf(a: Account) = serverBase(a.extra?.substringBefore('|') ?: "")

    /** Logs in with username/password and returns (accessToken, userId). */
    suspend fun login(server: String, user: String, password: String): Pair<String, String> {
        val auth = "MediaBrowser Client=\"UpZe\", Device=\"KMP\", DeviceId=\"upze-app\", Version=\"2.0\""
        val body = buildJsonObject { put("Username", user); put("Pw", password) }.toString()
        val resp = Http.postJson("${serverBase(server)}/Users/AuthenticateByName", body, mapOf("X-Emby-Authorization" to auth, "Authorization" to auth))
        val o = runCatching { Http.json.parseToJsonElement(resp) }.getOrNull() as? JsonObject
            ?: throw SourceUnavailableException("Jellyfin: unexpected reply")
        val userObj = o["User"] as? JsonObject ?: throw SourceUnavailableException("Jellyfin: unexpected reply")
        return (o.s("AccessToken") ?: throw SourceUnavailableException("Jellyfin: unexpected reply")) to
            (userObj.s("Id") ?: throw SourceUnavailableException("Jellyfin: unexpected reply"))
    }

    private suspend fun get(path: String, acct: Account = account() ?: throw SourceUnavailableException("No Jellyfin server")): JsonObject {
        val url = "${baseOf(acct)}/$path"
        val body = Http.getString(url, mapOf("Authorization" to authHeader(acct)))
        return Http.json.parseToJsonElement(body) as JsonObject
    }

    override suspend fun search(query: String, limit: Int): List<Track> {
        val a = account() ?: return emptyList()
        val o = get("Items?searchTerm=${query.encodeURLParameter()}&includeItemTypes=Audio&recursive=true&limit=$limit", a)
        val items = (o["Items"] as? JsonArray).orEmpty()
        return items.mapNotNull { (it as? JsonObject)?.let { item ->
            val id = item.s("Id") ?: return@mapNotNull null
            val ticks = item.l("RunTimeTicks") ?: 0L
            Track("jf:$id", item.s("Name").orEmpty(), item.s("AlbumArtist").orEmpty(), item.s("Album"), ticks / 10_000,
                "${baseOf(a)}/Items/$id/Images/Primary?fillWidth=600&fillHeight=600")
        } }
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val a = account() ?: throw SourceUnavailableException("No Jellyfin server")
        val url = "${serverBase(a.extra ?: "")}/Audio/${track.nativeId}/stream?static=true"
        return Resolved(url, StreamInfo(kind, "FLAC", null, null, track.id), headers = mapOf("Authorization" to authHeader(a)), cacheKey = "jf:${track.nativeId}")
    }

    override suspend fun probe() = "OK"
}
