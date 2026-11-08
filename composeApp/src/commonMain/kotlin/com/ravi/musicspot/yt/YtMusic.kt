package com.ravi.musicspot.yt

import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.Shelf
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.MusicSource
import com.ravi.musicspot.source.Resolved
import com.ravi.musicspot.source.SourceUnavailableException
import com.ravi.musicspot.source.parseClock

fun Song.toTrack(): Track = Track(
    id = "yt:$videoId",
    title = title,
    artist = artist,
    album = albumName,
    durationMs = parseClock(durationText),
    artUrl = upscale(thumbnailUrl),
    artistId = artistId?.let { "yt:$it" },
    albumId = albumId?.let { "yt:$it" },
    explicit = isExplicit == true
)

fun upscale(url: String?): String? = url?.let {
    when {
        "googleusercontent.com" in it || "ggpht.com" in it -> it.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj")
        else -> it
    }
}

private fun BrowseItem.toCollection(): Collection = Collection(
    id = "yt:$browseId",
    kind = when (type) {
        BrowseType.ALBUM -> Collection.Kind.ALBUM
        BrowseType.ARTIST -> Collection.Kind.ARTIST
        else -> Collection.Kind.PLAYLIST
    },
    title = title,
    subtitle = subtitle,
    artUrl = upscale(thumbnailUrl)
)

class YtMusic(
    val tube: Innertube,
    private val resolver: YtStreamResolver = YtStreamResolver(tube),
    private val enabled: () -> Boolean = { true }
) : MusicSource {
    override val kind = SourceKind.YOUTUBE
    override fun available() = enabled()

    override suspend fun search(query: String, limit: Int): List<Track> {
        val page = InnertubeParser.parseSearchPage(tube.search(query, SearchFilter.SONGS.params), includeVideos = false)
        return page.rows.mapNotNull { r ->
            when (r) {
                is SearchResult.TopTrack -> r.song.toTrack()
                is SearchResult.Track -> r.song.toTrack()
                else -> null
            }
        }.take(limit)
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val videoId = track.nativeId
        val (audio, client) = resolver.resolve(videoId, quality, metered)
            ?: throw SourceUnavailableException("YouTube stream unplayable for $videoId")

        val streamUrl = audio.url ?: throw SourceUnavailableException("No stream URL in format")
        val headers = mutableMapOf("User-Agent" to client.userAgent)
        client.origin?.let { headers["Origin"] = it }
        client.referer?.let { headers["Referer"] = it }

        return Resolved(
            uri = streamUrl,
            info = StreamInfo(kind, audio.codec, audio.kbps, audio.sampleRate ?: 44100, track.id),
            headers = headers,
            cacheKey = "yt:$videoId@${audio.itag}"
        )
    }

    override suspend fun probe(): String = "YouTube Music KMP: OK"
}
