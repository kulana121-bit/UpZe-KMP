package com.ravi.musicspot.ui.theme

import com.ravi.musicspot.logic.ArtTheme

expect suspend fun extractArtTheme(url: String?): ArtTheme.Picked?
