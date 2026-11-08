package com.ravi.musicspot.core

import com.ravi.musicspot.model.Track

expect object PlatformShortcuts {
    fun isPinSupported(): Boolean
    suspend fun pinTrack(track: Track): Boolean
    suspend fun pinPlaylist(playlistId: String, name: String, artUrl: String?): Boolean
    fun openOutputPicker(): Boolean
}

object TrackShortcuts {
    const val ACTION_PLAY_TRACK = "com.ravi.musicspot.action.PLAY_TRACK"
    const val EXTRA_TRACK = "track_json"
    const val ACTION_PLAY_PLAYLIST = "com.ravi.musicspot.action.PLAY_PLAYLIST"
    const val EXTRA_PLAYLIST = "playlist_id"

    fun supported(): Boolean = PlatformShortcuts.isPinSupported()
    suspend fun pin(t: Track): Boolean = PlatformShortcuts.pinTrack(t)
    suspend fun pinPlaylist(id: String, name: String, artUrl: String?): Boolean =
        PlatformShortcuts.pinPlaylist(id, name, artUrl)
    fun openOutputPicker(): Boolean = PlatformShortcuts.openOutputPicker()
}
