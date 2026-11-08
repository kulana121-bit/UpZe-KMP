package com.ravi.musicspot.playback

import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.PlaybackLog
import com.ravi.musicspot.core.currentTimeMillis
import com.ravi.musicspot.dsp.EngineSettings
import com.ravi.musicspot.logic.Fades
import com.ravi.musicspot.logic.SleepTimer
import com.ravi.musicspot.logic.Taste
import com.ravi.musicspot.logic.TimeOfDay
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.Resolved
import com.ravi.musicspot.source.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cross-platform base audio player managing queue, shuffle, repeat, sleep timer,
 * SponsorBlock, play counting, queue persistence, infinite autoplay, crossfade,
 * and next-track pre-resolution.
 */
abstract class BaseAudioPlayer : AudioPlayer {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    protected val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    protected val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position.asStateFlow()

    protected var baseVolume: Float = 1f
    protected var rampGain: Float = 1f
    protected val currentVolume: Float get() = (baseVolume * rampGain).coerceIn(0f, 1f)

    protected abstract fun setVolume(volume: Float)
    protected abstract fun onPlayTrack(track: Track, positionMs: Long)
    protected abstract fun onPause()
    protected abstract fun onResume()
    protected abstract fun onSeek(positionMs: Long)
    protected abstract fun onStop()

    private var fadeJob: Job? = null
    private var tickerJob: Job? = null
    private var prebufferJob: Job? = null
    private var autoplayJob: Job? = null

    private var countedForId: String? = null
    private var lastPlayedTrackId: String? = null
    private var repeatPending: Boolean = false

    private var prebufferedForId: String? = null
    private var preResolvedTrack: Pair<String, Resolved>? = null

    private var autoplayForId: String? = null
    private var sponsorSegments: List<SponsorBlock.Segment> = emptyList()

    init {
        startTicker()
    }

    fun takePreResolved(trackId: String): Resolved? {
        val cached = preResolvedTrack
        return if (cached != null && cached.first == trackId) cached.second else null
    }

    protected fun applyVolume() {
        setVolume(currentVolume)
    }

    protected fun rampVolume(toGain: Float, onEnd: (() -> Unit)? = null) {
        fadeJob?.cancel()
        val startGain = rampGain
        if (startGain == toGain) {
            onEnd?.invoke()
            return
        }
        fadeJob = scope.launch {
            val startTime = currentTimeMillis()
            val duration = Fades.RAMP_MS
            while (true) {
                val elapsed = currentTimeMillis() - startTime
                if (elapsed >= duration) {
                    rampGain = toGain
                    applyVolume()
                    onEnd?.invoke()
                    break
                }
                rampGain = if (toGain > startGain) {
                    Fades.rampUp(startGain, elapsed, duration)
                } else {
                    Fades.rampDown(startGain, elapsed, duration)
                }
                applyVolume()
                delay(Fades.RAMP_STEP_MS)
            }
        }
    }

    private fun onTrackStarted(track: Track) {
        runCatching {
            Graph.library.recordPlay(track, track.source.label, currentTimeMillis())
        }
        countedForId = null
        repeatPending = (track.id == lastPlayedTrackId)
        lastPlayedTrackId = track.id
        loadSponsorSegments(track)
        saveQueue()
        scheduleTidy()
    }

    private fun loadSponsorSegments(track: Track) {
        val s = Graph.settings.value
        if (!s.sponsorBlock || track.source != SourceKind.YOUTUBE) {
            sponsorSegments = emptyList()
            return
        }
        scope.launch(Dispatchers.Default) {
            val segs = runCatching {
                SponsorBlock.segments(track.nativeId, s.sponsorCategories)
            }.getOrNull().orEmpty()
            if (segs.isNotEmpty()) {
                PlaybackLog.add("SponsorBlock: ${segs.size} segment(s) to skip for ${track.title.take(30)}")
            }
            sponsorSegments = segs
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var lastSaveAt = currentTimeMillis()
            var fine = false
            while (isActive) {
                delay(if (fine) 40L else 200L)
                val curState = _state.value
                val curTrack = curState.current
                val isPlaying = curState.playing
                val now = currentTimeMillis()
                val dur = curState.durationMs
                val pos = _position.value
                val remaining = if (dur > 0) (dur - pos).coerceAtLeast(0L) else Long.MAX_VALUE
                val s = Graph.settings.value

                // 1. SponsorBlock
                if (s.sponsorBlock && isPlaying && curTrack != null && sponsorSegments.isNotEmpty()) {
                    SponsorBlock.skipAt(sponsorSegments, pos, dur)?.let { seg ->
                        seek(seg.endMs)
                        PlaybackLog.add("SponsorBlock: skipped ${seg.category} ${seg.startMs / 1000}-${seg.endMs / 1000} s")
                    }
                }

                // 2. Sleep Timer
                val sleep = Playback.sleep
                if (sleep.mode != SleepTimer.Mode.OFF) {
                    Playback.sleepTick.value = now
                    val last = curState.index >= curState.queue.size - 1
                    if (isPlaying && sleep.shouldPause(now, remaining, last, s.sleepFinishSong)) {
                        setPlaying(false)
                        sleep.cancel()
                        baseVolume = 1f
                        applyVolume()
                        continue
                    }
                }

                // 3. Volume Crossfade & Sleep Fade
                var vol = 1f
                if (s.sleepFade && sleep.mode == SleepTimer.Mode.DURATION) {
                    vol *= sleep.fadeFactor(now, 30_000)
                }
                val cf = if (s.gapless) 0L else s.crossfadeSec * 1000L
                if (dur > 0) {
                    vol *= Fades.crossfadeGain(pos, remaining, dur, cf)
                }
                fine = isPlaying && dur > 0 && Fades.inCrossfade(pos, remaining, dur, cf)
                baseVolume = vol
                applyVolume()

                // 4. Pre-buffer / Pre-resolve next track
                if (curTrack != null && dur > 0 && isPlaying && prebufferedForId != curTrack.id &&
                    (pos >= dur * 8 / 10 || remaining <= PREBUFFER_BEFORE_END_MS)
                ) {
                    prebufferedForId = curTrack.id
                    prebufferNext()
                }

                // 5. PlayStats counting threshold (~30s)
                if (curTrack != null && isPlaying && countedForId != curTrack.id && pos >= Taste.countThresholdMs(dur)) {
                    countedForId = curTrack.id
                    runCatching {
                        Graph.playStats.count(curTrack, repeatPending, now)
                    }
                    repeatPending = false
                }

                // 6. Periodic Queue persistence (every 5 seconds)
                if (now - lastSaveAt >= 5_000L) {
                    lastSaveAt = now
                    if (isPlaying) saveQueue()
                }

                // 7. Check Autoplay threshold
                if (isPlaying) {
                    maybeAutoplay()
                }
            }
        }
    }

    private fun prebufferNext() {
        val cur = _state.value
        val nextIdx = cur.index + 1
        val next = cur.queue.getOrNull(nextIdx) ?: return
        if (next.source.isLive) return
        if (Graph.downloads.isDownloaded(next.id)) return
        prebufferJob?.cancel()
        prebufferJob = scope.launch(Dispatchers.Default) {
            try {
                PlaybackLog.add("Pre-resolving upcoming \"${next.title.take(30)}\"")
                val quality = Graph.settings.value.effectiveQuality(Graph.metered)
                val resolved = Graph.sources.resolve(next, quality, Graph.metered)
                preResolvedTrack = next.id to resolved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaybackLog.add("Pre-resolving upcoming failed: ${e.message}")
            }
        }
    }

    private fun maybeAutoplay() {
        val s = Graph.settings.value
        val cur = _state.value
        if (!s.autoplay || cur.repeat != 0) return
        val seed = cur.current ?: return
        if (seed.source.isLive) return
        val remaining = cur.queue.size - cur.index - 1
        if (remaining > AUTOPLAY_WHEN_LEFT) return
        if (autoplayForId == seed.id) return
        autoplayForId = seed.id

        val need = (AUTOPLAY_TARGET - remaining).coerceAtLeast(1)
        autoplayJob?.cancel()
        autoplayJob = scope.launch(Dispatchers.Default) {
            try {
                val lib = Graph.library.data.value
                val now = currentTimeMillis()
                val statsData = Graph.playStats.data.value
                val stats = statsData.stats.values
                val weights = Taste.artistWeights(stats, lib.liked, now)
                val tod = TimeOfDay.artistAffinity(stats, statsData.dayparts, TimeOfDay.partAt(now))
                val likedIds = lib.liked.mapTo(HashSet()) { it.id }
                val recent = lib.history.take(60).map { it.track }

                val candidates = radioProvider(seed)
                var picked = Taste.autoplayMix(candidates, cur.queue, recent, weights, likedIds, need, tod)
                var hops = 0
                while (picked.size < need && picked.isNotEmpty() && hops < 2) {
                    hops++
                    val more = radioProvider(picked.last())
                    val extra = Taste.autoplayMix(more, cur.queue + picked, recent, weights, likedIds, need - picked.size, tod)
                    if (extra.isEmpty()) break
                    picked = picked + extra
                }

                if (!Graph.settings.value.autoplay || picked.isEmpty()) return@launch
                withContext(Dispatchers.Main) {
                    val currentQueue = _state.value.queue
                    val existingIds = currentQueue.map { it.id }.toSet()
                    val toAdd = picked.filter { it.id !in existingIds }
                    if (toAdd.isNotEmpty()) {
                        val autoplayFromIdx = if (_state.value.autoplayFrom >= 0) _state.value.autoplayFrom else currentQueue.size
                        _state.value = _state.value.copy(
                            queue = currentQueue + toAdd,
                            autoplayFrom = autoplayFromIdx
                        )
                        saveQueue()
                        PlaybackLog.add("Autoplay: appended ${toAdd.size} tracks from ${seed.title.take(30)}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaybackLog.add("Autoplay error: ${e.message}")
            }
        }
    }

    private fun scheduleTidy() {
        scope.launch {
            tidyQueue()
        }
    }

    private fun tidyQueue() {
        val cur = _state.value
        val n = cur.queue.size
        val curIdx = cur.index
        if (n <= 1 || curIdx !in 0 until n) return

        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        fun keyOf(t: Track): Pair<String, String?> = t.id to t.title.takeIf { it.isNotBlank() }?.let { Taste.key(t) }

        keyOf(cur.queue[curIdx]).let { (id, k) -> seenIds += id; k?.let(seenKeys::add) }
        val dupes = ArrayList<Int>()
        // 1.0.7: only upcoming copies are duplicates; played songs (above the playing one) are never removed
        for (i in (curIdx + 1) until n) {
            val (id, k) = keyOf(cur.queue[i])
            val dup = !seenIds.add(id) || (k != null && !seenKeys.add(k))
            if (dup) dupes += i
        }

        var newQueue = if (dupes.isNotEmpty()) {
            val dupSet = dupes.toSet()
            PlaybackLog.add("Queue: removed ${dupes.size} duplicate song(s)")
            cur.queue.filterIndexed { i, _ -> i !in dupSet }.toMutableList()
        } else cur.queue.toMutableList()

        var newIdx = newQueue.indexOfFirst { it.id == cur.queue[curIdx].id }.coerceAtLeast(0)

        // 1.0.7: played songs stay in the queue, above the playing song.
        // Only a very long history is trimmed (oldest first, past MAX_PLAYED_KEPT).
        if (newIdx > MAX_PLAYED_KEPT && !cur.shuffle && cur.repeat != 1) {
            val toDrop = newIdx - MAX_PLAYED_KEPT
            newQueue = newQueue.drop(toDrop).toMutableList()
            newIdx -= toDrop
        }
        _state.value = cur.copy(queue = newQueue, index = newIdx)
        saveQueue()
        maybeAutoplay()
    }

    private var upgradeJob: Job? = null
    private val upgradeTried = mutableSetOf<String>()

    fun maybeUpgradeToLossless(track: Track, currentResolved: Resolved) {
        if (track.source.isLive || !Graph.online.value) return
        val quality = Graph.settings.value.effectiveQuality(Graph.metered)
        if (quality == com.ravi.musicspot.model.Quality.AUTO && Graph.metered) return
        if (com.ravi.musicspot.source.QualityEstimate.isLosslessStream(currentResolved.info)) return
        if (Graph.downloads.isDownloaded(track.id)) return
        if (!upgradeTried.add(track.id)) return
        if (upgradeTried.size > 500) upgradeTried.clear()
        upgradeJob?.cancel()
        upgradeJob = scope.launch(Dispatchers.Default) {
            PlaybackLog.add("Upgrade: playing ${currentResolved.info.label}, looking for lossless", track.title.take(24))
            val r = try { Graph.sources.losslessUpgrade(track, currentResolved.info, Graph.metered) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                ?: run { PlaybackLog.add("Upgrade: no lossless copy found"); return@launch }
            withContext(Dispatchers.Main) {
                if (_state.value.current?.id != track.id) return@withContext
                PlaybackLog.add("Upgrade: switching to ${r.info.label} at the same position")
                val pos = _position.value
                val isPlaying = _state.value.playing
                onPlayTrack(track, pos)
                if (!isPlaying) onPause()
                runCatching { Graph.cached.record(track, r) }
            }
        }
    }

    fun saveQueue() {
        val cur = _state.value
        if (cur.queue.isEmpty()) return
        Graph.queueStore.save(
            com.ravi.musicspot.data.QueueSnapshot(
                tracks = cur.queue.take(500),
                index = cur.index.coerceAtLeast(0),
                positionMs = _position.value,
                shuffle = cur.shuffle,
                repeat = cur.repeat
            )
        )
    }

    fun restoreQueue() {
        if (_state.value.queue.isNotEmpty()) return
        scope.launch {
            val snap = Graph.queueStore.load() ?: return@launch
            if (_state.value.queue.isNotEmpty() || snap.tracks.isEmpty()) return@launch
            playAt(snap.tracks, snap.index, snap.positionMs, playing = false)
            setShuffle(snap.shuffle)
            _state.value = _state.value.copy(repeat = snap.repeat)
        }
    }

    override fun play(tracks: List<Track>, start: Int, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        val startIndex = if (shuffle) tracks.indices.random() else start.coerceIn(0, tracks.lastIndex)
        val track = tracks[startIndex]
        _state.value = _state.value.copy(
            queue = tracks,
            index = startIndex,
            playing = true,
            buffering = true,
            shuffle = shuffle,
            durationMs = track.durationMs,
            autoplayFrom = -1
        )
        _position.value = 0L
        onTrackStarted(track)
        onPlayTrack(track, 0L)
    }

    override fun playNext(t: Track) {
        val cur = _state.value
        if (cur.queue.isEmpty()) {
            play(listOf(t))
            return
        }
        val insertIdx = (cur.index + 1).coerceAtMost(cur.queue.size)
        val newQueue = cur.queue.toMutableList().apply { add(insertIdx, t) }
        _state.value = cur.copy(queue = newQueue)
        saveQueue()
    }

    override fun playNextAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val cur = _state.value
        if (cur.queue.isEmpty()) {
            play(tracks)
            return
        }
        val insertIdx = (cur.index + 1).coerceAtMost(cur.queue.size)
        val newQueue = cur.queue.toMutableList().apply { addAll(insertIdx, tracks) }
        _state.value = cur.copy(queue = newQueue)
        saveQueue()
    }

    override fun playAt(tracks: List<Track>, start: Int, positionMs: Long, playing: Boolean) {
        if (tracks.isEmpty()) return
        val s = start.coerceIn(0, tracks.lastIndex)
        val track = tracks[s]
        _state.value = _state.value.copy(
            queue = tracks,
            index = s,
            playing = playing,
            buffering = true,
            shuffle = false,
            durationMs = track.durationMs,
            autoplayFrom = -1
        )
        _position.value = positionMs
        if (playing) onTrackStarted(track)
        onPlayTrack(track, positionMs)
        if (!playing) onPause()
        saveQueue()
    }

    override fun setPlaying(on: Boolean) {
        val cur = _state.value
        if (cur.playing == on) return
        _state.value = cur.copy(playing = on)
        if (on) onResume() else onPause()
    }

    override fun addToQueue(tracks: List<Track>) {
        val cur = _state.value
        if (cur.queue.isEmpty()) {
            play(tracks)
            return
        }
        _state.value = cur.copy(queue = cur.queue + tracks)
        saveQueue()
    }

    override fun toggle() {
        setPlaying(!_state.value.playing)
    }

    override fun next() {
        val cur = _state.value
        if (cur.queue.isEmpty()) return
        val nextIdx = cur.index + 1
        if (nextIdx < cur.queue.size) {
            skipTo(nextIdx)
        } else if (cur.repeat == 1) { // REPEAT_ALL
            skipTo(0)
        } else {
            onStop()
            _state.value = cur.copy(playing = false)
        }
    }

    override fun previous() {
        val cur = _state.value
        if (_position.value > 3000L || cur.index <= 0) {
            seek(0L)
        } else {
            skipTo(cur.index - 1)
        }
    }

    override fun seek(ms: Long) {
        _position.value = ms.coerceAtLeast(0L)
        onSeek(_position.value)
    }

    override fun skipTo(i: Int) {
        val cur = _state.value
        if (i !in cur.queue.indices) return
        val target = cur.queue[i]
        _state.value = cur.copy(
            index = i,
            playing = true,
            buffering = true,
            durationMs = target.durationMs
        )
        _position.value = 0L
        onTrackStarted(target)
        onPlayTrack(target, 0L)
    }

    override fun move(from: Int, to: Int) {
        val cur = _state.value
        val n = cur.queue.size
        if (from !in 0 until n || to !in 0 until n || from == to) return
        val newQueue = cur.queue.toMutableList()
        val item = newQueue.removeAt(from)
        newQueue.add(to, item)
        val newIndex = when (cur.index) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) cur.index - 1 else cur.index + 1
            else -> cur.index
        }
        _state.value = cur.copy(queue = newQueue, index = newIndex)
        saveQueue()
    }

    override fun remove(i: Int) {
        val cur = _state.value
        if (i !in cur.queue.indices) return
        val newQueue = cur.queue.toMutableList().apply { removeAt(i) }
        val newIndex = when {
            newQueue.isEmpty() -> -1
            i < cur.index -> cur.index - 1
            i == cur.index -> cur.index.coerceAtMost(newQueue.lastIndex)
            else -> cur.index
        }
        _state.value = cur.copy(queue = newQueue, index = newIndex)
        saveQueue()
        if (i == cur.index) {
            if (newIndex >= 0) skipTo(newIndex) else stop()
        }
    }

    override fun clearUpcoming() {
        val cur = _state.value
        if (cur.index + 1 < cur.queue.size) {
            val trimmed = cur.queue.take(cur.index + 1)
            _state.value = cur.copy(queue = trimmed)
            saveQueue()
        }
    }

    override fun setShuffle(on: Boolean) {
        _state.value = _state.value.copy(shuffle = on)
        saveQueue()
    }

    override fun cycleRepeat() {
        val cur = _state.value
        val nextRepeat = (cur.repeat + 1) % 3
        _state.value = cur.copy(repeat = nextRepeat)
        saveQueue()
    }

    override fun retry() {
        Playback.error.value = null
        val cur = _state.value
        cur.current?.let {
            onTrackStarted(it)
            onPlayTrack(it, _position.value)
        }
    }

    override fun stop() {
        fadeJob?.cancel()
        prebufferJob?.cancel()
        autoplayJob?.cancel()
        onStop()
        _state.value = PlayerState()
        _position.value = 0L
    }

    override fun sleepFor(minutes: Int) {
        val now = currentTimeMillis()
        Playback.sleep.startDuration(minutes * 60_000L, now)
        Playback.sleepTick.value = now
    }

    override fun sleepEndOfTrack() {
        val now = currentTimeMillis()
        Playback.sleep.startEndOfTrack()
        Playback.sleepTick.value = now
    }

    override fun sleepEndOfQueue() {
        val now = currentTimeMillis()
        Playback.sleep.startEndOfQueue()
        Playback.sleepTick.value = now
    }

    override fun sleepAdjust(minutes: Int) {
        val now = currentTimeMillis()
        Playback.sleep.adjust(minutes * 60_000L, now)
        Playback.sleepTick.value = now
    }

    override fun sleepCancel() {
        val now = currentTimeMillis()
        Playback.sleep.cancel()
        Playback.sleepTick.value = now
    }

    override val sleepActive: Boolean
        get() = Playback.sleep.mode != SleepTimer.Mode.OFF

    override fun updateDspSettings(settings: EngineSettings) {}

    override fun release() {
        tickerJob?.cancel()
        stop()
    }

    companion object {
        const val MAX_PLAYED_KEPT = 150
        const val PREBUFFER_BEFORE_END_MS = 20_000L
        const val AUTOPLAY_WHEN_LEFT = 10
        const val AUTOPLAY_TARGET = 20

        val radioProvider: suspend (Track) -> List<Track> = { seed ->
            val yt = if (SourceKind.YOUTUBE !in Graph.settings.value.enabledSources) emptyList() else try {
                val ytSeed = if (seed.source == SourceKind.YOUTUBE) seed
                else Graph.yt.search("${seed.title} ${seed.artist}", 5).let { TrackMatcher.best(seed, it) }
                ytSeed?.let { Graph.yt.radio(it.nativeId) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaybackLog.add("Autoplay: YouTube radio failed: ${e.message}")
                emptyList()
            }
            yt.ifEmpty {
                try {
                    Graph.jiosaavn.recommendationsFor(seed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlaybackLog.add("Autoplay: JioSaavn reco failed: ${e.message}")
                    emptyList()
                }
            }
        }
    }
}
