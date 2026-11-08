package com.ravi.musicspot.yt

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 1.0.5: googlevideo URLs carry `expire=<epoch seconds>`; after it the URL answers 403/410. Used to refresh a URL
 * before a resume needs it ([com.ravi.musicspot.playback.PlaybackService]), to stop a long-paused stream before its
 * next chunk request fails ([com.ravi.musicspot.playback.ChunkedDataSource]) and to tell expiry from refusal.
 * Restored verbatim from the original Android app (yt/YtStreams.kt) — Android-only because it parses with OkHttp.
 */
object YtUrlExpiry {
    /** Longest lifetime believed; a longer (or negative) one means the device clock is off, so expiry is ignored. */
    const val MAX_LIFETIME_MS = 24 * 3_600_000L

    fun expiresAtMs(url: String?): Long? {
        val u = url?.toHttpUrlOrNull() ?: return null
        if (!u.host.endsWith("googlevideo.com")) return null
        val s = u.queryParameter("expire")?.toLongOrNull() ?: return null
        return if (s > 0) s * 1000 else null
    }

    /**
     * True when [url] is past (or within [marginMs] of) its `expire` time. [mintedAtMs] is when it was obtained on
     * this device's clock: when the lifetime it implies is implausible the clock is wrong and false is returned.
     */
    fun isExpired(url: String?, mintedAtMs: Long?, nowMs: Long = System.currentTimeMillis(), marginMs: Long = 0): Boolean {
        val exp = expiresAtMs(url) ?: return false
        if (mintedAtMs != null) {
            val life = exp - mintedAtMs
            if (life <= 0 || life > MAX_LIFETIME_MS) return false
        }
        return nowMs >= exp - marginMs
    }
}
