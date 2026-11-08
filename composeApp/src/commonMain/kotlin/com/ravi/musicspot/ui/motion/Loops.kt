package com.ravi.musicspot.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.ravi.musicspot.Graph
import com.ravi.musicspot.logic.DeviceTier

@Composable
fun rememberLoopPhase(
    running: Boolean,
    periodMs: Int,
    reverse: Boolean = true,
    rest: Float = 0f,
    easing: Easing = LinearEasing,
    ambient: Boolean = true,
): State<Float> {
    val anim = remember { Animatable(rest) }
    val frameInterval = if (ambient) DeviceTier.ambientFrameIntervalNanos(Graph.liteFx) else 0L
    LaunchedEffect(running, periodMs) {
        if (!running) { anim.snapTo(rest); return@LaunchedEffect }
        if (frameInterval <= 0L || periodMs <= 0) {
            while (true) {
                anim.animateTo(1f, tween(periodMs, easing = easing))
                if (reverse) anim.animateTo(0f, tween(periodMs, easing = easing)) else anim.snapTo(0f)
            }
        }
        val from = anim.value
        val startNanos = withFrameNanos { it }
        var lastPublished = startNanos
        anim.snapTo(loopPhaseAt(0L, periodMs, reverse, from, easing))
        while (true) {
            val now = withFrameNanos { it }
            if (!DeviceTier.shouldPublishFrame(lastPublished, now, frameInterval)) continue
            lastPublished = now
            anim.snapTo(loopPhaseAt((now - startNanos) / 1_000_000L, periodMs, reverse, from, easing))
        }
    }
    return anim.asState()
}

internal fun loopPhaseAt(elapsedMs: Long, periodMs: Int, reverse: Boolean, from: Float, easing: Easing): Float {
    if (periodMs <= 0) return 1f
    val e = elapsedMs.coerceAtLeast(0L)
    val leg = e / periodMs
    val t = ((e - leg * periodMs).toFloat() / periodMs).coerceIn(0f, 1f)
    val k = easing.transform(t)
    return when {
        leg == 0L -> from + (1f - from) * k
        reverse && leg % 2L == 1L -> 1f - k
        else -> k
    }
}
