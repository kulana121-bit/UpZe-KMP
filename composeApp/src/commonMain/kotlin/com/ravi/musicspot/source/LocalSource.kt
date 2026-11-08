package com.ravi.musicspot.source

import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.flow.StateFlow

expect class LocalSource(enabled: () -> Boolean) : MusicSource {
    val tracks: StateFlow<List<Track>>
    fun hasPermission(): Boolean
    suspend fun scan(): List<Track>
    override val kind: SourceKind
    override fun available(): Boolean
    override suspend fun search(query: String, limit: Int): List<Track>
    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved
    override suspend fun probe(): String
}
