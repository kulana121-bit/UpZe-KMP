package com.ravi.musicspot.ui.theme

import com.ravi.musicspot.logic.ArtTheme

actual suspend fun extractArtTheme(url: String?): ArtTheme.Picked? {
    // Pure KMP fallback on iOS
    return null
}
