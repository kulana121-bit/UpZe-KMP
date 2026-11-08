package com.ravi.musicspot.playback

import com.ravi.musicspot.logic.SleepTimer
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import kotlinx.coroutines.flow.MutableStateFlow

/** What is really playing: the resolved source plus the decoder's actual format (codec, bitrate, sample rate). */
data class NowStream(
    val trackId: String,
    val info: StreamInfo,
    val lossless: Boolean = false,
    val hiRes: Boolean = false,
    val failovers: List<String> = emptyList(),
    /** Set when YouTube was exhausted/excluded and this plays from another source (UI: "Playing from JioSaavn"). */
    val fallbackFrom: SourceKind? = null,
)

/** Shared playback state between the player engine and the UI. */
object Playback {
    val stream = MutableStateFlow<NowStream?>(null)
    val error = MutableStateFlow<String?>(null)
    val sleep = SleepTimer()
    val sleepTick = MutableStateFlow(0L)
    val bitPerfectStatus = MutableStateFlow<String?>(null)
}
