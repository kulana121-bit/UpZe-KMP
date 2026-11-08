package com.ravi.musicspot.ui

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled, onBack)
}

@Composable
actual fun PredictiveBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onBack: () -> Unit) {
    androidx.activity.compose.PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { e -> onProgress(e.progress) }
            onProgress(0f)
            onBack()
        } catch (e: CancellationException) {
            onProgress(0f)
            throw e
        }
    }
}
