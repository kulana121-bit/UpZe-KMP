package com.ravi.musicspot.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.ui.motion.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs

const val SWIPE_THRESHOLD = 0.35f

@Composable
fun SwipeRow(onSwipeRight: () -> Unit, onSwipeLeft: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val slop = LocalViewConfiguration.current.touchSlop
    val right by rememberUpdatedState(onSwipeRight)
    val left by rememberUpdatedState(onSwipeLeft)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val threshold = widthPx * SWIPE_THRESHOLD
        val phase by remember(threshold) {
            derivedStateOf {
                val x = offset.value
                when {
                    x == 0f -> 0
                    x > 0 -> if (x >= threshold) 2 else 1
                    else -> if (-x >= threshold) -2 else -1
                }
            }
        }
        if (phase != 0) {
            val toRight = phase > 0
            val past = abs(phase) == 2
            val bg = if (toRight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer
            val fg = if (toRight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
            Row(
                Modifier.matchParentSize().background(bg.copy(alpha = if (past) 1f else 0.7f)).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toRight) androidx.compose.foundation.layout.Arrangement.Start else androidx.compose.foundation.layout.Arrangement.End
            ) {
                Icon(
                    if (toRight) Icons.Rounded.SkipNext else Icons.AutoMirrored.Rounded.PlaylistAdd,
                    null,
                    tint = fg,
                    modifier = Modifier.size(24.dp).graphicsLayer { val s = if (past) 1.15f else 0.9f; scaleX = s; scaleY = s }
                )
                Spacer(Modifier.width(8.dp))
                Text(if (toRight) "Play next" else "Add to queue", color = fg, style = MaterialTheme.typography.labelLarge)
            }
        }
        Box(
            Modifier.graphicsLayer { translationX = offset.value }
                .background(if (phase != 0) MaterialTheme.colorScheme.background else Color.Transparent)
                .pointerInput(widthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var dx = 0f; var dy = 0f; var dragging = false; var crossed = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val d = ch.positionChange()
                            if (!dragging) {
                                dx += d.x; dy += d.y
                                if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                                if (abs(dx) > slop * 1.5f && abs(dx) > 2 * abs(dy)) dragging = true else continue
                            }
                            ch.consume()
                            val next = (offset.value + d.x).coerceIn(-widthPx * 0.6f, widthPx * 0.6f)
                            scope.launch { offset.snapTo(next) }
                            val nowPast = abs(next) >= threshold
                            if (nowPast != crossed) { crossed = nowPast; if (nowPast) haptics.tick() }
                        }
                        if (dragging) {
                            val final = offset.value
                            if (abs(final) >= threshold) { if (final > 0) right() else left() }
                            scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 255f)) }
                        }
                    }
                }
        ) { content() }
    }
}
