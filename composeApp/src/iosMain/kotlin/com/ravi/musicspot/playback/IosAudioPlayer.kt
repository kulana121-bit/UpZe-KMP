package com.ravi.musicspot.playback

import com.ravi.musicspot.Graph
import com.ravi.musicspot.dsp.EngineCore
import com.ravi.musicspot.dsp.EngineSettings
import com.ravi.musicspot.dsp.EngineStatus
import com.ravi.musicspot.model.Track
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.AVFoundation.currentTime
import platform.AVFoundation.play
import platform.AVFoundation.pause
import platform.AVFoundation.seekToTime
import platform.Foundation.NSURL
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

@OptIn(ExperimentalForeignApi::class)
class IosAudioPlayer : BaseAudioPlayer() {
    private var player: AVPlayer? = null
    private var engineCore: EngineCore? = null
    private var currentSettings = EngineSettings()

    private var pollJob: Job? = null
    private var playJob: Job? = null

    init {
        val audioSession = AVAudioSession.sharedInstance()
        audioSession.setCategory(AVAudioSessionCategoryPlayback, error = null)
        audioSession.setActive(true, error = null)
        setupRemoteCommandCenter()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val p = player
                if (p != null) {
                    val sec = CMTimeGetSeconds(p.currentTime())
                    if (!sec.isNaN()) {
                        val posMs = (sec * 1000).toLong()
                        _position.value = posMs
                    }
                }
                delay(250)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
    }

    override fun onPlayTrack(track: Track, positionMs: Long) {
        playJob?.cancel()
        playJob = scope.launch {
            try {
                _state.value = _state.value.copy(buffering = true)
                val quality = Graph.settings.value.effectiveQuality(Graph.metered)
                val resolved = takePreResolved(track.id) ?: Graph.sources.resolve(track, quality, Graph.metered)
                val url = resolved.uri
                if (url.isEmpty()) {
                    Playback.error.value = "Failed to resolve stream for ${track.title}"
                    _state.value = _state.value.copy(buffering = false, playing = false)
                    return@launch
                }
                Playback.stream.value = NowStream(
                    trackId = track.id,
                    info = resolved.info,
                    fallbackFrom = resolved.fallbackFrom
                )
                Playback.error.value = null

                val nsUrl = NSURL.URLWithString(url) ?: return@launch
                val playerItem = AVPlayerItem(uRL = nsUrl)
                engineCore = EngineCore(44100, 2, currentSettings)
                EngineStatusCenter.publish(engineCore?.status ?: EngineStatus())
                _state.value = _state.value.copy(dspStatus = engineCore?.status ?: EngineStatus())

                val p = AVPlayer(playerItem = playerItem)
                player = p
                updateNowPlayingInfo(track)
                if (positionMs > 0) {
                    val cmTime = CMTimeMake(value = positionMs, timescale = 1000)
                    p.seekToTime(cmTime)
                }
                p.volume = 0f
                p.play()
                rampVolume(1f)
                _state.value = _state.value.copy(playing = true, buffering = false)
                startPolling()
            } catch (e: Exception) {
                Playback.error.value = e.message ?: "Playback error"
                _state.value = _state.value.copy(buffering = false, playing = false)
            }
        }
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume
    }

    override fun onPause() {
        rampVolume(0f) {
            player?.pause()
            _state.value = _state.value.copy(playing = false)
            stopPolling()
        }
    }

    override fun onResume() {
        player?.play()
        rampVolume(1f)
        _state.value = _state.value.copy(playing = true)
        startPolling()
    }

    override fun onSeek(positionMs: Long) {
        val cmTime = CMTimeMake(value = positionMs, timescale = 1000)
        player?.seekToTime(cmTime)
    }

    override fun onStop() {
        playJob?.cancel()
        stopPolling()
        player?.pause()
        player = null
        Playback.stream.value = null
    }

    override fun updateDspSettings(settings: EngineSettings) {
        currentSettings = settings
        engineCore?.update(settings)
        EngineStatusCenter.publish(engineCore?.status ?: EngineStatus())
    }

    override fun release() {
        super.release()
        onStop()
    }

    private fun setupRemoteCommandCenter() {
        val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
        commandCenter.playCommand.addTargetWithHandler { _ ->
            onResume()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.pauseCommand.addTargetWithHandler { _ ->
            onPause()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.togglePlayPauseCommand.addTargetWithHandler { _ ->
            toggle()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.nextTrackCommand.addTargetWithHandler { _ ->
            next()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.previousTrackCommand.addTargetWithHandler { _ ->
            previous()
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun updateNowPlayingInfo(track: Track) {
        val info = mutableMapOf<Any?, Any>()
        info[MPMediaItemPropertyTitle] = track.title
        info[MPMediaItemPropertyArtist] = track.artist
        info[MPMediaItemPropertyPlaybackDuration] = (track.durationMs / 1000.0)
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    companion object {
        val instance by lazy { IosAudioPlayer() }
    }
}

actual fun createPlatformAudioPlayer(): AudioPlayer = IosAudioPlayer.instance
