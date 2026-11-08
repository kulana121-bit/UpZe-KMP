@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.ravi.musicspot.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.core.currentTimeMillis
import kotlinx.coroutines.delay

val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalPageScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun Modifier.sharedCover(key: String?): Modifier {
    if (key == null || reducedMotion()) return this
    val shared = LocalSharedScope.current ?: return this
    val page = LocalPageScope.current ?: return this
    return with(shared) {
        this@sharedCover.sharedElement(
            rememberSharedContentState(key),
            animatedVisibilityScope = page,
            boundsTransform = { _, _ -> MotionSpec.containerSpring() },
        )
    }
}

private val PP_LEFT = floatArrayOf(8f, 5f, 6f, 5f, 13.5f, 8.14f, 10f, 5f, 13.5f, 15.86f, 10f, 19f, 8f, 19f, 6f, 19f)
private val PP_RIGHT = floatArrayOf(13.5f, 8.14f, 14f, 5f, 19f, 12f, 18f, 5f, 19f, 12f, 18f, 19f, 13.5f, 15.86f, 14f, 19f)

private fun ppQuad(path: Path, pts: FloatArray, p: Float, ox: Float, oy: Float, s: Float) {
    path.rewind()
    var i = 0
    while (i < pts.size) {
        val x = ox + (pts[i] + (pts[i + 2] - pts[i]) * p) * s
        val y = oy + (pts[i + 1] + (pts[i + 3] - pts[i + 1]) * p) * s
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        i += 4
    }
    path.close()
}

@Composable
fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier.size(24.dp), tint: Color = LocalContentColor.current) {
    val still = reducedMotion()
    val p by animateFloatAsState(if (playing) 1f else 0f, if (still) tween(0) else spring(dampingRatio = 0.8f, stiffness = 357f), label = "pp")
    Spacer(modifier.semantics { contentDescription = if (playing) "Pause" else "Play" }.drawWithCache {
        val left = Path(); val right = Path()
        onDrawBehind {
            val s = size.minDimension / 24f
            val ox = (size.width - 24f * s) / 2f; val oy = (size.height - 24f * s) / 2f
            val pv = p
            ppQuad(left, PP_LEFT, pv, ox, oy, s); ppQuad(right, PP_RIGHT, pv, ox, oy, s)
            drawPath(left, tint); drawPath(right, tint)
        }
    })
}

@Composable
fun Modifier.shimmer(shape: Shape = MaterialTheme.shapes.medium): Modifier {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    if (reducedMotion()) return this.clip(shape).background(base)
    val t = rememberInfiniteTransition(label = "shimmer")
    val x = t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1820, easing = LinearEasing), RepeatMode.Restart), label = "sx")
    val brush = remember(base, hi) { Brush.linearGradient(listOf(base, hi, base), start = Offset.Zero, end = Offset(400f, 240f)) }
    return this.clip(shape).drawBehind {
        val dx = x.value * 600f
        translate(left = dx) { drawRect(brush, topLeft = Offset(-dx, 0f), size = size) }
    }
}

@Composable
fun SkeletonBox(width: Dp, height: Dp, shape: Shape = MaterialTheme.shapes.medium) = Box(Modifier.size(width, height).shimmer(shape))

class StaggerGroup {
    private var startMs = 0L
    fun admit(): Boolean {
        val now = currentTimeMillis()
        if (startMs == 0L) startMs = now
        return now - startMs < WINDOW_MS
    }
    companion object { const val WINDOW_MS = 700L }
}

val LocalStaggerGroup = staticCompositionLocalOf<StaggerGroup?> { null }

fun Modifier.staggerIn(index: Int, enabled: Boolean = true): Modifier = composed {
    val still = reducedMotion()
    val group = LocalStaggerGroup.current
    val play = remember { !still && enabled && (group?.admit() ?: true) }
    if (!play) return@composed this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceIn(0, 8) * 40L))
        a.animateTo(1f, tween(476, easing = FastOutSlowInEasing))
    }
    this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 16.dp.toPx() }
}

@Composable
fun <S> StateCrossfade(state: S, modifier: Modifier = Modifier, key: (S) -> Any? = { it?.let { v -> v::class } }, content: @Composable (S) -> Unit) {
    val still = reducedMotion()
    androidx.compose.animation.AnimatedContent(
        targetState = state, modifier = modifier, label = "state", contentKey = key,
        transitionSpec = {
            if (still) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            else androidx.compose.animation.fadeIn(tween(308, delayMillis = 84)) togetherWith androidx.compose.animation.fadeOut(tween(168))
        },
    ) { content(it) }
}
