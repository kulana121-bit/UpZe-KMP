package com.ravi.musicspot.yt

import com.ravi.musicspot.model.Quality
import kotlinx.serialization.json.*

data class YtAudio(
    val itag: Int,
    val mime: String,
    val bitrate: Int,
    val url: String?,
    val sampleRate: Int? = null,
    val contentLength: Long? = null,
    val cipher: String? = null
) {
    val opus get() = "opus" in mime
    val kbps get() = bitrate / 1000
    val codec get() = if (opus) "Opus" else if ("mp4a" in mime) "AAC" else mime.substringAfter('/').substringBefore(';')
}

object YtAudioFormats {
    fun rank(audio: List<YtAudio>, quality: Quality, metered: Boolean): List<YtAudio> {
        val cap = if (metered) 96 else when (quality) {
            Quality.AUTO -> 160
            Quality.HIGH -> 160
            Quality.MAX, Quality.LOSSLESS -> Int.MAX_VALUE
        }
        val (under, over) = audio.partition { it.kbps <= cap }
        val order = compareByDescending<YtAudio> { it.kbps.coerceAtMost(cap) / 16 }
            .thenByDescending { it.opus }
            .thenByDescending { it.bitrate }
        return under.sortedWith(order) + over.sortedBy { it.bitrate }
    }

    fun parse(player: JsonObject): List<YtAudio> {
        val sd = player["streamingData"] as? JsonObject ?: return emptyList()
        val list = sd["adaptiveFormats"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val mime = (o["mimeType"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!mime.startsWith("audio/")) return@mapNotNull null
            YtAudio(
                itag = (o["itag"] as? JsonPrimitive)?.intOrNull ?: 0,
                mime = mime,
                bitrate = (o["bitrate"] as? JsonPrimitive)?.intOrNull
                    ?: (o["averageBitrate"] as? JsonPrimitive)?.intOrNull ?: 0,
                url = (o["url"] as? JsonPrimitive)?.contentOrNull,
                sampleRate = (o["audioSampleRate"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                contentLength = (o["contentLength"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                cipher = ((o["signatureCipher"] ?: o["cipher"]) as? JsonPrimitive)?.contentOrNull
            )
        }
    }
}

class YtStreamResolver(private val tube: Innertube) {
    suspend fun resolve(videoId: String, quality: Quality, metered: Boolean): Pair<YtAudio, PlayerClient>? {
        // Walk mobile and native clients that give direct un-ciphered URLs
        val clients = listOf(PlayerClient.IOS, PlayerClient.ANDROID_VR, PlayerClient.ANDROID_MUSIC, PlayerClient.TVHTML5)
        for (client in clients) {
            try {
                val playerResp = tube.player(videoId, client)
                val formats = YtAudioFormats.parse(playerResp)
                val ranked = YtAudioFormats.rank(formats, quality, metered)
                val playable = ranked.firstOrNull { it.url != null }
                if (playable != null) {
                    return playable to client
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }
}
