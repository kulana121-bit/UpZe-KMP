package com.ravi.musicspot.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * Cross-platform blurred artwork backdrop using native Compose Multiplatform blur.
 */
@Composable
fun BlurredArt(
    url: String?,
    modifier: Modifier = Modifier,
    blurRadius: Dp,
    id: String? = url,
    alpha: Float = 1f
) {
    Box(modifier) {
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(blurRadius),
                contentScale = ContentScale.Crop,
                alpha = alpha
            )
        }
    }
}
