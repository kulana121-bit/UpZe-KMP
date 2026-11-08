package com.ravi.musicspot.playback

import com.ravi.musicspot.dsp.EngineSettings
import com.ravi.musicspot.dsp.EngineStatus
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.flow.StateFlow

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = 0,
    val durationMs: Long = 0,
    val autoplayFrom: Int = -1,
    val dspStatus: EngineStatus = EngineStatus(),
) {
    val current: Track? get() = queue.getOrNull(index)
    val track: Track? get() = current
}

interface AudioPlayer {
    val state: StateFlow<PlayerState>
    val position: StateFlow<Long>
    fun play(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false)
    fun playNext(t: Track)
    fun playNextAll(tracks: List<Track>)
    fun playAt(tracks: List<Track>, start: Int, positionMs: Long, playing: Boolean)
    fun setPlaying(on: Boolean)
    fun addToQueue(tracks: List<Track>)
    fun toggle()
    fun next()
    fun previous()
    fun seek(ms: Long)
    fun skipTo(i: Int)
    fun move(from: Int, to: Int)
    fun remove(i: Int)
    fun clearUpcoming()
    fun setShuffle(on: Boolean)
    fun cycleRepeat()
    fun retry()
    fun stop()

    fun sleepFor(minutes: Int)
    fun sleepEndOfTrack()
    fun sleepEndOfQueue()
    fun sleepAdjust(minutes: Int)
    fun sleepCancel()
    val sleepActive: Boolean

    fun updateDspSettings(settings: EngineSettings)
    fun release()
}

typealias PlayerConnection = AudioPlayer

expect fun createPlatformAudioPlayer(): AudioPlayer
