package com.ravi.musicspot.core

import com.ravi.musicspot.model.Track

actual object PlatformShortcuts {
    actual fun isPinSupported(): Boolean = false // Android launcher shortcut stub
    actual suspend fun pinTrack(track: Track): Boolean = false
    actual suspend fun pinPlaylist(playlistId: String, name: String, artUrl: String?): Boolean = false
    actual fun openOutputPicker(): Boolean = false
}
