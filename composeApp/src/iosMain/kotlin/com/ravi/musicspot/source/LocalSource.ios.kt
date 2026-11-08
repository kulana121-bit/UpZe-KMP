package com.ravi.musicspot.source

import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.MediaPlayer.*

@OptIn(ExperimentalForeignApi::class)
actual class LocalSource actual constructor(private val enabled: () -> Boolean) : MusicSource {
    actual override val kind: SourceKind = SourceKind.LOCAL
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    actual val tracks: StateFlow<List<Track>> = _tracks
    private val uriCache = mutableMapOf<String, String>()

    actual fun hasPermission(): Boolean {
        val auth = MPMediaLibrary.authorizationStatus()
        return auth == MPMediaLibraryAuthorizationStatusAuthorized
    }

    actual override fun available(): Boolean = enabled()

    actual suspend fun scan(): List<Track> {
        val query = MPMediaQuery.songsQuery()
        val items = query.items?.filterIsInstance<MPMediaItem>() ?: emptyList()
        val result = items.mapNotNull { item ->
            val url = item.assetURL?.absoluteString
            val id = "local:${item.persistentID}"
            if (url != null) uriCache[id] = url
            val title = item.title ?: "Unknown"
            val artist = item.artist ?: "Unknown Artist"
            val album = item.albumTitle ?: ""
            val duration = (item.playbackDuration * 1000).toLong()
            Track(
                id = id,
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                artUrl = null,
                format = "m4a"
            )
        }
        _tracks.value = result
        return result
    }

    actual override suspend fun search(query: String, limit: Int): List<Track> {
        val all = _tracks.value.ifEmpty { scan() }
        val q = query.trim().lowercase()
        return all.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || (it.album?.lowercase()?.contains(q) == true)
        }.take(limit)
    }

    actual override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val uri = uriCache[track.id] ?: run {
            val query = MPMediaQuery.songsQuery()
            val pid = track.nativeId.toULongOrNull()
            val found = query.items?.filterIsInstance<MPMediaItem>()?.firstOrNull { it.persistentID == pid }
            found?.assetURL?.absoluteString
        } ?: throw SourceUnavailableException("No stream URL for local iOS track ${track.title}")

        return Resolved(
            uri = uri,
            info = StreamInfo(source = SourceKind.LOCAL, codec = "AAC", bitrateKbps = 256, sourceTrackId = track.id),
            cacheKey = track.id
        )
    }

    actual override suspend fun probe(): String = "iOS iPod library (${_tracks.value.size} tracks)"
}
