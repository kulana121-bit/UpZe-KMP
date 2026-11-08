@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ravi.musicspot.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.ravi.musicspot.Graph
import com.ravi.musicspot.dsp.EngineSettings
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.widget.NowPlayingWidget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidAudioPlayer(private val context: Context) : BaseAudioPlayer() {
    private var dspSettings = EngineSettings()
    private val dspProcessor = EngineAudioProcessor { dspSettings }

    private val exoPlayer: ExoPlayer = run {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ) = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(dspProcessor))
                .build()
        }.setEnableDecoderFallback(true)

        val http = ChunkedDataSource.Factory(ChunkedDataSource.httpFactory())
        val upstream = DefaultDataSource.Factory(context, http)
        val mediaSourceFactory = DefaultMediaSourceFactory(upstream)
            .setLoadErrorHandlingPolicy(SeamlessRetryPolicy(onRetry = { _, _ -> }, onExpired = { _, _ -> }))

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private var mediaSession: MediaSession? = runCatching {
        MediaSession.Builder(context, exoPlayer).build()
    }.getOrNull()

    private var pollJob: Job? = null
    private var playJob: Job? = null

    init {
        mediaSessionInstance = mediaSession
        NowPlayingWidget.Controls.handle = { action ->
            when (action) {
                NowPlayingWidget.ACTION_TOGGLE -> { toggle(); true }
                NowPlayingWidget.ACTION_NEXT -> { next(); true }
                NowPlayingWidget.ACTION_PREV -> { previous(); true }
                else -> false
            }
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
                updateWidget()
                if (isPlaying) startPolling() else stopPolling()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val buffering = playbackState == Player.STATE_BUFFERING
                val duration = exoPlayer.duration.coerceAtLeast(0L)
                _state.value = _state.value.copy(
                    buffering = buffering,
                    durationMs = if (duration > 0) duration else _state.value.durationMs
                )
                updateWidget()
                if (playbackState == Player.STATE_ENDED) {
                    if (_state.value.repeat == 2) {
                        seek(0L)
                        onResume()
                    } else {
                        next()
                    }
                }
            }
        })

        scope.launch {
            EngineAudioProcessor.status.collect { status ->
                _state.value = _state.value.copy(dspStatus = status)
            }
        }
    }

    private fun updateWidget() {
        val currentTrack = _state.value.current
        NowPlayingWidget.update(
            context,
            NowPlayingWidget.State(
                title = currentTrack?.title,
                artist = currentTrack?.artist,
                artUrl = currentTrack?.artUrl,
                playing = _state.value.playing
            )
        )
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                _position.value = pos
                delay(250)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
    }

    private fun startService() {
        val intent = android.content.Intent(context, PlaybackService::class.java)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onPlayTrack(track: Track, positionMs: Long) {
        startService()
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

                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMediaId(track.id)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setArtworkUri(track.artUrl?.let { Uri.parse(it) })
                            .build()
                    )
                    .build()

                exoPlayer.setMediaItem(item)
                exoPlayer.prepare()
                if (positionMs > 0) exoPlayer.seekTo(positionMs)
                exoPlayer.volume = 0f
                exoPlayer.play()
                rampVolume(1f)
                updateWidget()
            } catch (e: Exception) {
                Playback.error.value = e.message ?: "Playback error"
                _state.value = _state.value.copy(buffering = false, playing = false)
            }
        }
    }

    override fun setVolume(volume: Float) {
        exoPlayer.volume = volume
    }

    override fun onPause() {
        rampVolume(0f) { exoPlayer.pause() }
    }

    override fun onResume() {
        exoPlayer.play()
        rampVolume(1f)
    }

    override fun onSeek(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    override fun onStop() {
        playJob?.cancel()
        stopPolling()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        Playback.stream.value = null
        updateWidget()
    }

    override fun updateDspSettings(settings: EngineSettings) {
        dspSettings = settings
        dspProcessor.apply(settings)
    }

    override fun release() {
        super.release()
        runCatching { mediaSession?.release() }
        mediaSession = null
        mediaSessionInstance = null
        exoPlayer.release()
    }

    companion object {
        var mediaSessionInstance: MediaSession? = null
            internal set
        private var appContext: Context? = null
        fun init(context: Context) { appContext = context.applicationContext }
        fun get(): AndroidAudioPlayer = AndroidAudioPlayer(requireNotNull(appContext) { "AndroidAudioPlayer not initialized" })
    }
}

actual fun createPlatformAudioPlayer(): AudioPlayer = AndroidAudioPlayer.get()
