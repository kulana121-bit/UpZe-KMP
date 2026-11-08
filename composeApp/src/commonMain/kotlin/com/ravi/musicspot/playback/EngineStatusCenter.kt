package com.ravi.musicspot.playback

import com.ravi.musicspot.dsp.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live DSP engine status for the UI (EQ active, upscale badge, detected cutoff, limiter).
 * Each platform's audio engine publishes here: the Android ExoPlayer processor and the iOS player.
 */
object EngineStatusCenter {
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    fun publish(status: EngineStatus) {
        _status.value = status
    }
}
