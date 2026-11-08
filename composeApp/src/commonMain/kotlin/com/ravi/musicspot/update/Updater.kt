package com.ravi.musicspot.update

import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.core.PlaybackLog
import com.ravi.musicspot.core.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

object Updater {
    const val CURRENT_VERSION = "v1.02"
    const val CURRENT_VERSION_CODE = 32L
    private const val INTERVAL_MS = 12 * 3600 * 1000L

    sealed interface Download {
        data object Idle : Download
        data class Running(val done: Long, val total: Long) : Download
        data class Ready(val path: String) : Download
        data class Failed(val message: String) : Download
    }

    @Serializable
    private data class Cache(val etag: String? = null, val body: String? = null, val checkedAt: Long = 0)

    private val _available = MutableStateFlow<UpdateLogic.Release?>(null)
    val available: StateFlow<UpdateLogic.Release?> = _available
    private val _download = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = _download
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking
    private var downloadJob: Job? = null

    private fun log(msg: String) = runCatching { PlaybackLog.log("Updater: $msg") }

    fun checkOnStart() {
        IoScope.launch {
            runCatching {
                Graph.awaitReady()
                if (!Graph.settings.value.autoUpdateCheck) return@launch
                check(manual = false)
            }.onFailure {
                if (it is CancellationException) throw it
                log("start check failed: ${it::class.simpleName}")
            }
        }
    }

    enum class Result { UPDATE, UP_TO_DATE, FAILED }

    suspend fun check(manual: Boolean): Result = withContext(Dispatchers.Default) {
        _checking.value = true
        try {
            val headers = mapOf(
                "User-Agent" to "UpZe",
                "Accept" to "application/vnd.github+json"
            )
            val body = Http.getString(UpdateLogic.LATEST_URL, headers)
            val rel = UpdateLogic.parseRelease(body)
            if (rel == null) {
                log("latest release has no usable asset")
                return@withContext Result.UP_TO_DATE
            }
            if (!UpdateLogic.isNewer(rel.version, CURRENT_VERSION)) return@withContext Result.UP_TO_DATE
            if (!manual && Graph.settings.value.skippedUpdate == rel.version) {
                log("v${rel.version} skipped by the user")
                return@withContext Result.UP_TO_DATE
            }
            log("v${rel.version} available (installed $CURRENT_VERSION)")
            _available.value = rel
            Result.UPDATE
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("check failed: ${e::class.simpleName}: ${e.message}")
            Result.FAILED
        } finally {
            _checking.value = false
        }
    }

    fun later() {
        downloadJob?.cancel()
        _available.value = null
        _download.value = Download.Idle
    }

    fun skip(version: String) {
        runCatching { Graph.settings.update { it.copy(skippedUpdate = version) } }
        later()
    }
}
