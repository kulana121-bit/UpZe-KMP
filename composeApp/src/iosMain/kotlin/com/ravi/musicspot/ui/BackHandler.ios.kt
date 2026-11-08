package com.ravi.musicspot.ui

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS uses native navigation swipe
}

@Composable
actual fun PredictiveBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onBack: () -> Unit) {
    // iOS has no predictive-back gesture progress; the root BackHandler handles the back action itself.
}
