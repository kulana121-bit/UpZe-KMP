package com.ravi.musicspot.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import com.ravi.musicspot.core.currentTimeMillis

/**
 * Remembers where the user last touched the screen (window pixels), recorded by [trackTaps] on the root.
 * Pages, sheets, dialogs and the player's full-screen pane use it as the origin of a container transform.
 */
object TapOrigin {
    private var x = -1f
    private var y = -1f
    private var upAt = 0L
    private var moved = false
    private var winW = 1
    private var winH = 1
    private val saved = HashMap<String, TransformOrigin>()

    internal fun down(p: Offset) { x = p.x; y = p.y; moved = false }
    internal fun move(p: Offset, slop: Float) { if (!moved && (kotlin.math.abs(p.x - x) > slop || kotlin.math.abs(p.y - y) > slop)) moved = true }
    internal fun up(now: Long) { upAt = now }
    internal fun window(w: Int, h: Int) { winW = w.coerceAtLeast(1); winH = h.coerceAtLeast(1) }

    fun freshTap(now: Long = currentTimeMillis()): Boolean = x >= 0f && !moved && now - upAt in 0..700

    fun point(): Offset? = if (x < 0f) null else Offset(x, y)

    fun windowOrigin(): TransformOrigin = if (x < 0f) TransformOrigin.Center
        else TransformOrigin((x / winW).coerceIn(0f, 1f), (y / winH).coerceIn(0f, 1f))

    fun save(key: String, origin: TransformOrigin) { saved[key] = origin; if (saved.size > 64) saved.keys.firstOrNull()?.let { saved.remove(it) } }
    fun take(key: String): TransformOrigin = saved.remove(key) ?: TransformOrigin.Center
}

fun Modifier.trackTaps(): Modifier = this
    .onSizeChanged { TapOrigin.window(it.width, it.height) }
    .pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val d = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            TapOrigin.down(d.position)
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                e.changes.firstOrNull()?.let { TapOrigin.move(it.position, slop) }
                if (e.changes.none { it.pressed }) { TapOrigin.up(e.changes.firstOrNull()?.uptimeMillis ?: currentTimeMillis()); break }
            }
        }
    }

@Composable
fun Modifier.growFrom(scope: AnimatedVisibilityScope, tap: Offset, fromScale: Float = 0.6f): Modifier {
    val p by scope.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) spring(dampingRatio = MotionSpec.SURFACE_DAMPING, stiffness = MotionSpec.SURFACE_STIFFNESS)
            else tween(MotionSpec.EXIT, easing = MotionSpec.emphasizedAccelerate)
        },
        label = "growFrom",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val pos = remember { FloatArray(2) { Float.NaN } }
    return this
        .onGloballyPositioned { c -> val w = c.positionInWindow(); pos[0] = w.x; pos[1] = w.y }
        .graphicsLayer {
            val s = fromScale + (1f - fromScale) * p
            scaleX = s; scaleY = s
            if (!pos[0].isNaN() && size.width > 0f && size.height > 0f) {
                transformOrigin = TransformOrigin((tap.x - pos[0]) / size.width, (tap.y - pos[1]) / size.height)
            }
        }
}
