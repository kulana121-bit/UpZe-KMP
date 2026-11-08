/*
 * Spotify library import, ported from OpenTune 1.0.4, GPL-3.0-or-later.
 * Migrated to pure Kotlin Multiplatform using Ktor client.
 */
package com.ravi.musicspot.spotify

import com.ravi.musicspot.core.Crypto
import com.ravi.musicspot.core.File
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.currentTimeMillis
import com.ravi.musicspot.model.Track
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.charsets.Charsets
import io.ktor.http.*
import io.ktor.util.decodeBase64String
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class SpotifyClient(private val dir: File?) {
    class SignedOut(message: String) : Exception(message)

    @Serializable data class Secret(val version: Int, val codes: List<Int>)
    @Serializable data class WebPlayer(val clientVersion: String, val secret: Secret, val hashes: Map<String, String>, val complete: Boolean, val at: Long)
    data class Playlist(val id: String, val name: String, val owner: String?, val imageUrl: String?)

    private class Access(val token: String, val expiresAt: Long, val clientId: String?, val cookie: String)
    private class ClientTok(val token: String, val expiresAt: Long)

    private val lock = Mutex()
    private val lane = Mutex()
    private var webPlayer: WebPlayer? = null
    private var access: Access? = null
    private var client: ClientTok? = null

    private val canvasMutex = Mutex()
    private val canvasCache = mutableMapOf<String, String>()

    fun signedOut() { access = null; client = null }

    data class Profile(val name: String?, val imageUrl: String?)

    suspend fun profile(cookie: String): Profile {
        val bearer = accessToken(cookie)
        val (code, text) = runCatching {
            send(
                url = "https://api.spotify.com/v1/me",
                headers = mapOf(
                    "Authorization" to "Bearer ${bearer.token}",
                    "Accept" to "application/json",
                    "User-Agent" to UA
                )
            )
        }.getOrNull() ?: return Profile(null, null)
        if (code !in 200..299) return Profile(null, null)
        val o = runCatching { Http.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Profile(null, null)
        val img = (o["images"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("url") }.firstOrNull()
        return Profile(o.text("display_name") ?: o.text("id"), img)
    }

    suspend fun playlists(cookie: String): List<Playlist> {
        val all = mutableListOf(Playlist(LIKED_ID, "Liked Songs", null, LIKED_COVER))
        var offset = 0
        while (offset < MAX_ITEMS) {
            val root = query("libraryV3", cookie, buildJsonObject {
                putJsonArray("filters") { add(JsonPrimitive("Playlists")) }
                put("order", JsonNull); put("textFilter", "")
                putJsonArray("features") { listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS").forEach { add(JsonPrimitive(it)) } }
                put("limit", 50); put("offset", offset); put("flatten", true)
                putJsonArray("expandedFolders") {}
                put("folderUri", JsonNull); put("includeFoldersWhenFlattening", false)
            })
            val (items, total, raw) = playlistPage(root)
            all += items; offset += raw
            if (raw == 0 || offset >= total) break
        }
        return all.distinctBy { it.id }
    }

    suspend fun tracks(cookie: String, playlistId: String): List<Track> {
        val all = mutableListOf<Track>()
        var offset = 0
        val isLiked = playlistId == LIKED_ID
        val op = if (isLiked) "fetchLibraryTracks" else "fetchPlaylist"
        while (offset < MAX_ITEMS) {
            val root = query(op, cookie, buildJsonObject {
                if (isLiked) {
                    put("offset", offset); put("limit", 50)
                } else {
                    put("uri", "spotify:playlist:$playlistId")
                    put("offset", offset); put("limit", 50)
                }
            })
            val (items, total, raw) = if (isLiked) likedPage(root) else trackPage(root)
            all += items; offset += raw
            if (raw == 0 || offset >= total) break
        }
        return all
    }

    suspend fun canvasUrl(cookie: String, t: Track): String? {
        val key = t.id
        canvasMutex.withLock { canvasCache[key] }?.let { return it.ifEmpty { null } }
        val id = if (t.id.startsWith("sp:")) t.id.removePrefix("sp:") else findTrackId(cookie, t)
        if (id == null) {
            canvasMutex.withLock { canvasCache[key] = NONE }
            return null
        }
        val root = runCatching { query("canvas", cookie, buildJsonObject { put("trackUri", "spotify:track:$id") }) }.getOrNull() ?: return null
        val url = (root.at("data", "trackUnion", "canvas", "url") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.startsWith("https://") && it.substringBefore('?').endsWith(".mp4") }
        canvasMutex.withLock { canvasCache[key] = url ?: NONE }
        return url
    }

    private suspend fun findTrackId(cookie: String, t: Track): String? {
        fun fold(x: String) = x.lowercase()
            .replace(Regex("""\(.*?\)|\[.*?]|\s(feat|ft)\..*|\s-\s.*"""), "")
            .filter { it.isLetterOrDigit() }
        val artist0 = t.artist.substringBefore(',').substringBefore('&').trim()
        val wantT = fold(t.title); val wantA = fold(artist0)
        if (wantT.isNotEmpty()) runCatching {
            val root = query("searchTracks", cookie, buildJsonObject {
                put("searchTerm", "${t.title} $artist0"); put("offset", 0); put("limit", 10); put("numberOfTopResults", 5)
                put("includeAudiobooks", false); put("includePreReleases", false); put("includeAuthors", false)
            })
            val items = (root.at("data", "searchV2", "tracksV2", "items") as? JsonArray).orEmpty()
            val hits = items.mapNotNull { it.at("item", "data") as? JsonObject }
            for (h in hits) {
                val hitT = fold(h.text("name").orEmpty())
                val hitA = fold((h.at("artists", "items") as? JsonArray)?.firstOrNull()?.at("profile", "name")?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
                val hitMs = (h.at("duration", "totalMilliseconds") as? JsonPrimitive)?.longOrNull ?: 0L
                val msDiff = kotlin.math.abs(hitMs - t.durationMs)
                if (hitT == wantT && (hitA.contains(wantA) || wantA.contains(hitA) || wantA.isEmpty()) && (t.durationMs <= 0 || msDiff < 4_000)) {
                    val uri = h.text("uri") ?: continue
                    return uri.substringAfterLast(':')
                }
            }
        }
        return null
    }

    // ---- network --------------------------------------------------------------------------------------------
    private suspend fun send(
        url: String,
        method: HttpMethod = HttpMethod.Get,
        headers: Map<String, String> = emptyMap(),
        bodyText: String? = null
    ): Pair<Int, String> = lane.withLock {
        var tries = 0
        while (true) {
            val (code, text, retry) = try {
                val response = Http.client.request(url) {
                    this.method = method
                    headers.forEach { (k, v) -> header(k, v) }
                    if (bodyText != null) {
                        setBody(bodyText)
                        contentType(ContentType.Application.Json.withCharset(Charsets.UTF_8))
                    }
                }
                Triple(response.status.value, response.bodyAsText(), response.headers["Retry-After"])
            } catch (e: Exception) {
                if (tries++ >= 2) throw e
                delay(1000)
                continue
            }
            if (code != 429 || tries++ >= 2) return@withLock code to text
            delay(((retry?.toLongOrNull() ?: 5L) * 1000).coerceIn(1000, 30_000))
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun query(op: String, cookie: String, variables: JsonObject, retried: Boolean = false): JsonObject {
        val bearer = accessToken(cookie)
        val player = lock.withLock { readWebPlayer(false) }
        val ct = clientToken(bearer, player)
        val body = buildJsonObject {
            put("variables", variables); put("operationName", op)
            putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", player.hashes[op] ?: KNOWN_HASHES.getValue(op)) } }
        }.toString()

        val headers = mutableMapOf(
            "Authorization" to "Bearer ${bearer.token}",
            "Accept" to "application/json",
            "App-Platform" to "WebPlayer",
            "Origin" to WEB,
            "Referer" to "$WEB/",
            "User-Agent" to UA
        )
        if (ct != null) headers["Client-Token"] = ct

        val (code, text) = send(url = PATHFINDER, method = HttpMethod.Post, headers = headers, bodyText = body)
        if (code == 401 && !retried) { access = null; client = null; return query(op, cookie, variables, true) }
        val root = runCatching { Http.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val errors = (root?.get("errors") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("message") }
        if (errors.any { it.contains("PersistedQueryNotFound", true) } && !retried) {
            lock.withLock { webPlayer = null; readWebPlayer(true) }
            return query(op, cookie, variables, true)
        }
        if (code !in 200..299) throw Exception("Spotify $op answered $code")
        return root ?: throw Exception("Spotify $op: not JSON")
    }

    private suspend fun accessToken(cookie: String): Access = lock.withLock {
        access?.takeIf { it.cookie == cookie && currentTimeMillis() < it.expiresAt - 30_000 }?.let { return@withLock it }
        for (force in listOf(false, true)) {
            val player = readWebPlayer(force)
            val code = totp(player.secret.codes, currentTimeMillis())
            val url = "$WEB/api/token?reason=init&productType=web-player&totp=$code&totpServer=$code&totpVer=${player.secret.version}"
            val (status, text) = send(
                url = url,
                method = HttpMethod.Get,
                headers = mapOf(
                    "Cookie" to "sp_dc=$cookie",
                    "User-Agent" to UA,
                    "Accept" to "application/json",
                    "Referer" to "$WEB/"
                )
            )
            val body = runCatching { Http.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (body?.get("totpVerExpired") != null && !force) continue
            if (status == 401) throw SignedOut("Your Spotify sign-in has expired")
            if ((body?.get("isAnonymous") as? JsonPrimitive)?.booleanOrNull == true) throw SignedOut("Not signed in to Spotify")
            val token = body?.text("accessToken")
            if (token != null) {
                val exp = (body["accessTokenExpirationTimestampMs"] as? JsonPrimitive)?.longOrNull ?: (currentTimeMillis() + 3_600_000)
                return@withLock Access(token, exp, body.text("clientId"), cookie).also { access = it }
            }
        }
        throw Exception("Spotify wouldn't give a token")
    }

    private suspend fun clientToken(bearer: Access, player: WebPlayer): String? {
        client?.takeIf { currentTimeMillis() < it.expiresAt - 30_000 }?.let { return it.token }
        val clientId = bearer.clientId ?: return null
        val body = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", player.clientVersion); put("client_id", clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown"); put("device_model", "unknown"); put("os", "windows"); put("os_version", "unknown")
                    put("device_id", deviceId()); put("device_type", "computer")
                }
            }
        }.toString()
        return runCatching {
            val (_, text) = send(
                url = CLIENT_TOKEN,
                method = HttpMethod.Post,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to UA
                ),
                bodyText = body
            )
            val root = Http.json.parseToJsonElement(text).jsonObject
            if (root.text("response_type") != "RESPONSE_GRANTED_TOKEN_RESPONSE") return@runCatching null
            val g = root["granted_token"] as? JsonObject ?: return@runCatching null
            val t = g.text("token") ?: return@runCatching null
            client = ClientTok(t, currentTimeMillis() + ((g["expires_after_seconds"] as? JsonPrimitive)?.longOrNull ?: 3600) * 1000)
            t
        }.getOrNull()
    }

    private fun deviceId(): String {
        val f = dir?.let { File(it, "spotify-device-id") }
        f?.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length == 32 }?.let { return it }
        return kotlin.random.Random.nextBytes(16).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.replace("-", "").also { id -> runCatching { f?.writeText(id) } }
    }

    private suspend fun text(url: String): String? = runCatching {
        Http.client.get(url) {
            header("User-Agent", UA)
        }.bodyAsText()
    }.getOrNull()

    private suspend fun readWebPlayer(force: Boolean): WebPlayer {
        val file = dir?.let { File(it, "spotify-web-player.json") }
        if (!force) {
            val cached = webPlayer ?: file?.takeIf { it.exists() }?.let { runCatching { Http.json.decodeFromString(WebPlayer.serializer(), it.readText()) }.getOrNull() }
            if (cached != null && currentTimeMillis() - cached.at < TTL) { webPlayer = cached; return cached }
        }
        val html = text("$WEB/").orEmpty()
        val version = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""").find(html)?.groupValues?.get(1)
            ?.let { runCatching { it.decodeBase64String() }.getOrNull() }
            ?.let { runCatching { Http.json.parseToJsonElement(it).jsonObject.text("clientVersion") }.getOrNull() }
        val bundleUrl = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[0-9a-f]+\.js""").find(html)?.value
        val bundle = bundleUrl?.let { text(it) }.orEmpty()
        val secret = secretsIn(bundle).firstOrNull()
        val found = KNOWN_HASHES.keys.associateWith { queryHashIn(bundle, it) }
        val read = WebPlayer(version ?: "1.2.77.0", secret ?: KNOWN_SECRET, KNOWN_HASHES.mapValues { (op, k) -> found[op] ?: k },
            secret != null && version != null && found.values.all { it != null }, currentTimeMillis())
        webPlayer = read
        runCatching { file?.writeText(Http.json.encodeToString(WebPlayer.serializer(), read)) }
        return read
    }

    companion object {
        const val LIKED_ID = "liked"
        const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
        private const val NONE = ""
        private const val MAX_ITEMS = 10_000
        private const val WEB = "https://open.spotify.com"
        private const val PATHFINDER = "https://api-partner.spotify.com/pathfinder/v2/query"
        private const val CLIENT_TOKEN = "https://clienttoken.spotify.com/v1/clienttoken"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        private const val TTL = 12 * 60 * 60_000L

        val KNOWN_HASHES = mapOf(
            "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
            "fetchPlaylist" to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
            "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
            "canvas" to "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f",
            "searchTracks" to "b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751",
        )
        private val KNOWN_SECRET = Secret(12, listOf(107, 81, 49, 57, 67, 93, 87, 81, 69, 67, 40, 93, 48, 50, 46, 91, 94, 113, 41, 47))

        fun JsonElement?.at(vararg keys: String): JsonElement? = keys.fold(this) { a, k -> (a as? JsonObject)?.get(k) }
        private fun JsonObject.text(k: String) = (get(k) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(k: String) = (get(k) as? JsonPrimitive)?.intOrNull
        private fun largest(sources: JsonElement?): String? = (sources as? JsonArray).orEmpty()
            .mapNotNull { s -> (s.at("url") as? JsonPrimitive)?.contentOrNull?.let { it to ((s.at("width") as? JsonPrimitive)?.intOrNull ?: 0) } }
            .maxByOrNull { it.second }?.first

        fun playlistPage(root: JsonObject): Triple<List<Playlist>, Int, Int> {
            val lib = root.at("data", "me", "libraryV3") as? JsonObject ?: throw Exception("Spotify's library answer was empty")
            val raw = lib["items"] as? JsonArray ?: JsonArray(emptyList())
            val items = raw.mapNotNull { e ->
                val w = e.at("item") as? JsonObject ?: return@mapNotNull null
                val data = w["data"] as? JsonObject ?: return@mapNotNull null
                if (data.text("__typename") != "Playlist") return@mapNotNull null
                val uri = w.text("_uri") ?: return@mapNotNull null
                val name = data.text("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Playlist(uri.substringAfterLast(':'), name, (data.at("ownerV2", "data", "name") as? JsonPrimitive)?.contentOrNull,
                    largest((data.at("images", "items") as? JsonArray)?.firstOrNull().at("sources")))
            }
            return Triple(items, lib.int("totalCount") ?: raw.size, raw.size)
        }

        fun trackPage(root: JsonObject): Triple<List<Track>, Int, Int> {
            val pl = root.at("data", "playlistV2") as? JsonObject ?: throw Exception("Spotify's playlist answer was empty")
            val content = pl["content"] as? JsonObject ?: throw Exception("Spotify's playlist answer had no songs")
            val raw = content["items"] as? JsonArray ?: JsonArray(emptyList())
            val items = raw.mapNotNull { e -> (e.at("itemV2", "data") as? JsonObject)?.let { item(it, it.text("uri") ?: it.text("_uri")) } }
            return Triple(items, content.int("totalCount") ?: raw.size, raw.size)
        }

        fun likedPage(root: JsonObject): Triple<List<Track>, Int, Int> {
            val tracks = root.at("data", "me", "library", "tracks") as? JsonObject ?: throw Exception("Spotify's liked songs answer was empty")
            val raw = tracks["items"] as? JsonArray ?: JsonArray(emptyList())
            val items = raw.mapNotNull { e ->
                val w = e.at("track") as? JsonObject ?: return@mapNotNull null
                val data = w["data"] as? JsonObject ?: return@mapNotNull null
                item(data, w.text("_uri") ?: data.text("uri"))
            }
            return Triple(items, tracks.int("totalCount") ?: raw.size, raw.size)
        }

        private fun item(data: JsonObject, uri: String?): Track? {
            if (uri == null || !uri.startsWith("spotify:track:")) return null
            val title = data.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val artists = (data.at("artists", "items") as? JsonArray).orEmpty()
                .mapNotNull { (it.at("profile", "name") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            val ms = ((data.at("trackDuration", "totalMilliseconds") ?: data.at("duration", "totalMilliseconds")) as? JsonPrimitive)?.longOrNull ?: 0L
            val album = (data.at("albumOfTrack", "name") as? JsonPrimitive)?.contentOrNull
            val art = largest(data.at("albumOfTrack", "coverArt", "sources"))
            return Track("sp:${uri.substringAfterLast(':')}", title, artists.joinToString(", "), album, ms, art)
        }

        fun secretsIn(script: String): List<Secret> =
            Regex("""\{secret:(['"])((?:\\.|(?!\1).)*)\1,version:(\d+)\}""").findAll(script)
                .map { m -> Secret(m.groupValues[3].toInt(), unescapeJs(m.groupValues[2]).map { it.code }) }
                .sortedByDescending { it.version }.toList()

        fun queryHashIn(script: String, op: String): String? =
            Regex("""["']$op["']\s*,\s*["'](?:query|mutation)["']\s*,\s*["']([0-9a-f]{64})["']""").find(script)?.groupValues?.get(1)

        private fun codePointToString(cp: Int): String {
            return if (cp <= 0xFFFF) {
                cp.toChar().toString()
            } else {
                val high = ((cp - 0x10000) ushr 10) + 0xD800
                val low = ((cp - 0x10000) and 0x3FF) + 0xDC00
                "${high.toChar()}${low.toChar()}"
            }
        }

        fun unescapeJs(body: String): String = Regex("""\\(u\{[0-9a-fA-F]+\}|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[\s\S])""").replace(body) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("u") && e.length > 1 -> codePointToString(e.drop(1).trim('{', '}').toInt(16))
                e.startsWith("x") && e.length == 3 -> e.drop(1).toInt(16).toChar().toString()
                else -> when (e) { "n" -> "\n"; "r" -> "\r"; "t" -> "\t"; "0" -> "\u0000"; else -> e }
            }
        }

        private fun longToBigEndian(v: Long): ByteArray = byteArrayOf(
            ((v ushr 56) and 0xFF).toByte(), ((v ushr 48) and 0xFF).toByte(),
            ((v ushr 40) and 0xFF).toByte(), ((v ushr 32) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
        )

        fun totp(codes: List<Int>, epochMs: Long): String {
            val key = codes.mapIndexed { i, c -> c xor ((i % 33) + 9) }.joinToString("").encodeToByteArray()
            val h = Crypto.hmacSha1(key, longToBigEndian(epochMs / 1000 / 30))
            val o = h.last().toInt() and 0x0f
            val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
            return (bin % 1_000_000).toString().padStart(6, '0')
        }
    }
}
