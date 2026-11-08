package com.ravi.musicspot.source

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

actual class LocalSource actual constructor(private val enabled: () -> Boolean) : MusicSource {
    @Volatile var context: Context? = null
    actual override val kind: SourceKind = SourceKind.LOCAL
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    actual val tracks: StateFlow<List<Track>> = _tracks

    actual fun hasPermission(): Boolean = true
    actual override fun available(): Boolean = enabled()

    actual suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext emptyList<Track>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.YEAR,
        )
        val out = mutableListOf<Track>()
        val cursor = try { ctx.contentResolver.query(uri, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE") }
        catch (e: Exception) { null }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val albumId = c.getLong(5)
                val mime = c.getString(6).orEmpty()
                out += Track(
                    "local:$id", c.getString(1).orEmpty(), c.getString(2)?.takeIf { it != "<unknown>" }.orEmpty(), c.getString(3),
                    c.getLong(4), ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), albumId).toString(),
                    albumId = "local:al:$albumId", year = c.getInt(7).takeIf { it > 0 }?.toString(), format = mime.substringAfter('/').ifBlank { null },
                )
            }
        }
        _tracks.value = out
        out
    }

    actual override suspend fun search(query: String, limit: Int): List<Track> {
        val all = _tracks.value.ifEmpty { scan() }
        val q = query.lowercase()
        return all.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }.take(limit)
    }

    actual override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.nativeId.toLongOrNull() ?: throw SourceUnavailableException("bad local id")).toString()
        return Resolved(uri, StreamInfo(kind, track.format?.uppercase(), null, null, track.id), cacheKey = track.id)
    }

    actual override suspend fun probe(): String = "${scan().size} tracks on device"
}
