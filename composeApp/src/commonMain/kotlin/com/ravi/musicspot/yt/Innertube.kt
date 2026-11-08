package com.ravi.musicspot.yt

import com.ravi.musicspot.core.Crypto
import com.ravi.musicspot.core.Http
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class Innertube(
    private val ytBase: String = YT_BASE,
    private val musicBase: String = MUSIC_BASE,
    private val visitorUrl: String? = "https://www.youtube.com/sw.js_data"
) {
    class UnplayableException(val status: String, val reason: String?) : Exception("$status: ${reason ?: "unplayable"}")

    var cookie: String? = null
        set(value) {
            if (field != value) visitorData = null
            field = value
        }

    private var visitorData: String? = null
    var region: String = "IN"
    val language: String = "en"
    val signedIn: Boolean get() = cookie?.let { sapisidFrom(it) } != null

    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh && visitorData != null) return visitorData
        val src = visitorUrl ?: return visitorData
        val body = runCatching {
            Http.getString(src, mapOf("User-Agent" to PlayerClient.WEB_REMIX.userAgent))
        }.getOrNull() ?: return visitorData

        val payload = runCatching {
            Http.json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
        }.getOrNull() ?: return null

        visitorData = findVisitor(payload)
        return visitorData
    }

    private fun findVisitor(e: JsonElement): String? = when (e) {
        is JsonArray -> e.firstNotNullOfOrNull { findVisitor(it) }
        is JsonPrimitive -> e.contentOrNull?.takeIf { VISITOR.matches(it) }
        else -> null
    }

    // ---- WEB_REMIX endpoints ----------------------------------------------------------------------------------
    suspend fun browse(browseId: String, params: String? = null): JsonObject = postMusic("browse") {
        put("browseId", browseId)
        params?.let { put("params", it) }
    }

    suspend fun accountMenu(): JsonObject = postMusic("account/account_menu") {}

    suspend fun browseContinuation(token: String): JsonObject =
        postMusic("browse", mapOf("ctoken" to token, "continuation" to token, "type" to "next")) {
            put("continuation", token)
        }

    suspend fun charts(country: String?): JsonObject = postMusic("browse") {
        put("browseId", "FEmusic_charts")
        country?.let { c ->
            putJsonObject("formData") {
                putJsonArray("selectedValues") { add(c) }
            }
        }
    }

    suspend fun search(query: String, params: String? = null): JsonObject = postMusic("search") {
        put("query", query)
        params?.let { put("params", it) }
    }

    suspend fun suggestions(input: String): JsonObject = postMusic("music/get_search_suggestions") {
        put("input", input)
    }

    suspend fun next(videoId: String, playlistId: String? = "RDAMVM$videoId"): JsonObject = postMusic("next") {
        put("videoId", videoId)
        playlistId?.let { put("playlistId", it) }
        put("isAudioOnly", true)
    }

    // ---- Player endpoint --------------------------------------------------------------------------------------
    suspend fun player(videoId: String, client: PlayerClient, authenticated: Boolean = false): JsonObject {
        ensureVisitorData()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.clientName)
                    put("clientVersion", client.clientVersion)
                    client.osName?.let { put("osName", it) }
                    client.osVersion?.let { put("osVersion", it) }
                    client.deviceMake?.let { put("deviceMake", it) }
                    client.deviceModel?.let { put("deviceModel", it) }
                    put("hl", language)
                    put("gl", region)
                    visitorData?.let { put("visitorData", it) }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val base = if (client.usesMusicHost) musicBase else ytBase
        val headers = mutableMapOf(
            "User-Agent" to client.userAgent,
            "X-YouTube-Client-Name" to client.clientId,
            "X-YouTube-Client-Version" to client.clientVersion
        )
        client.origin?.let { headers["Origin"] = it }
        client.referer?.let { headers["Referer"] = it }
        visitorData?.let { headers["X-Goog-Visitor-Id"] = it }

        val c = cookie
        if (authenticated && c != null) {
            headers["Cookie"] = c
            sapisidFrom(c)?.let {
                headers["Authorization"] = sapisidHash(it, client.origin ?: if (client.usesMusicHost) MUSIC_ORIGIN else YT_ORIGIN)
            }
        }

        val jsonStr = Http.postJson("$base/player?prettyPrint=false", body.toString(), headers)
        val res = Http.json.parseToJsonElement(jsonStr).jsonObject

        val ps = res["playabilityStatus"] as? JsonObject
        val status = (ps?.get("status") as? JsonPrimitive)?.contentOrNull
        if (status != null && status != "OK") {
            val reason = (ps?.get("reason") as? JsonPrimitive)?.contentOrNull
            throw UnplayableException(status, reason)
        }
        return res
    }

    private suspend fun postMusic(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        extras: JsonObjectBuilder.() -> Unit
    ): JsonObject {
        if (visitorData == null) ensureVisitorData()
        val url = buildString {
            append(musicBase).append("/").append(endpoint).append("?prettyPrint=false")
            query.forEach { (k, v) ->
                append("&").append(k.encodeURLParameter()).append("=").append(v.encodeURLParameter())
            }
        }
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", PlayerClient.WEB_REMIX.clientVersion)
                    put("hl", language)
                    put("gl", region)
                    visitorData?.let { put("visitorData", it) }
                }
                putJsonObject("user") { put("lockedSafetyMode", false) }
                putJsonObject("request") { put("useSsl", true) }
            }
            extras()
        }

        val headers = mutableMapOf(
            "User-Agent" to PlayerClient.WEB_REMIX.userAgent,
            "X-YouTube-Client-Name" to PlayerClient.WEB_REMIX.clientId,
            "X-YouTube-Client-Version" to PlayerClient.WEB_REMIX.clientVersion,
            "Origin" to MUSIC_ORIGIN,
            "Referer" to "$MUSIC_ORIGIN/"
        )
        visitorData?.let { headers["X-Goog-Visitor-Id"] = it }

        val respStr = Http.postJson(url, body.toString(), headers)
        return Http.json.parseToJsonElement(respStr).jsonObject
    }

    companion object {
        const val YT_BASE = "https://www.youtube.com/youtubei/v1"
        const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
        const val MUSIC_ORIGIN = "https://music.youtube.com"
        const val YT_ORIGIN = "https://www.youtube.com"
        private val VISITOR = Regex("^[A-Za-z0-9%_-]{20,}$")

        fun sapisidFrom(cookie: String): String? = cookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("SAPISID=") || it.startsWith("__Secure-3PAPISID=") }
            ?.substringAfter('=')

        fun sapisidHash(sapisid: String, origin: String): String {
            val now = 1731000000L // epoch timestamp
            val digest = Crypto.sha1("$now $sapisid $origin")
            return "SAPISIDHASH ${now}_$digest"
        }
    }
}
