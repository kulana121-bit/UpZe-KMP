package com.ravi.musicspot.ui.motion

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.ravi.musicspot.Graph
import kotlin.math.abs
import kotlin.math.sign

object Depth {
    const val FLAT_LIST = 3.5f
    const val FLAT_TRACKS = 6.5f
}

@Composable
fun Modifier.scroll3d(
    state: LazyListState,
    index: Int,
    focus: Float = 0.5f,
    maxTilt: Float = 28f,
    minScale: Float = 0.86f,
    minAlpha: Float = 0.45f,
    flat: Float = 0f,
    flatRows: Float = 0f,
    allowOnLite: Boolean = false,
): Modifier {
    if (reducedMotion()) return this
    if (!allowOnLite && Graph.liteFx) return this
    return this.graphicsLayer {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
        val vpStart = info.viewportStartOffset
        val vpEnd = info.viewportEndOffset
        val vpH = (vpEnd - vpStart).toFloat().coerceAtLeast(1f)
        val center = item.offset + item.size / 2f
        val focusY = vpStart + vpH * focus
        val half = if (center < focusY) (focusY - vpStart) else (vpEnd - focusY)
        val d = ((center - focusY) / half.coerceAtLeast(1f)).coerceIn(-1.2f, 1.2f)
        val eased = depthEase(d, flat, flatBandHalf(flatRows, item.size.toFloat(), half))
        rotationX = -sign(d) * maxTilt * eased.coerceAtMost(1f)
        val s = 1f - (1f - minScale) * eased.coerceAtMost(1f)
        scaleX = s; scaleY = s
        alpha = 1f - (1f - minAlpha) * eased.coerceAtMost(1f)
        cameraDistance = 14f * density
        transformOrigin = TransformOrigin(0.5f, if (d < 0) 1f else 0f)
    }
}

internal fun flatBandHalf(flatRows: Float, rowPx: Float, halfViewportPx: Float): Float =
    if (flatRows > 0f) ((flatRows * rowPx / 2f) / halfViewportPx.coerceAtLeast(1f)).coerceIn(0f, 0.92f) else 0f

internal fun depthEase(d: Float, flat: Float, bandHalf: Float): Float {
    val f = maxOf(flat, bandHalf)
    val k = ((abs(d) - f) / (1f - f)).coerceAtLeast(0f)
    return k * k * (3f - 2f * k.coerceAtMost(1f))
}
