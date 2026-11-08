package com.ravi.musicspot.ui

import androidx.compose.runtime.Composable

@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/**
 * Back handling that also reports predictive-back gesture progress (0f..1f) where the platform
 * supports it (Android 14+); other platforms only ever deliver the final back event.
 */
@Composable
expect fun PredictiveBackHandler(enabled: Boolean = true, onProgress: (Float) -> Unit, onBack: () -> Unit)
