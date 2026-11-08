package com.ravi.musicspot.ui.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.ui.Tab
import com.ravi.musicspot.ui.glass.glass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

object TabLift {
    private val icons = Array(Tab.entries.size) { FloatArray(4) { Float.NaN } }
    private val bar = FloatArray(2) { Float.NaN }
    internal val origin = FloatArray(2) { Float.NaN }

    var tab by mutableStateOf<Tab?>(null)
        private set

    val progress = Animatable(0f)
    private var job: Job? = null

    internal fun registerIcon(t: Tab, x: Float, y: Float, w: Float, h: Float) {
        val a = icons[t.ordinal]
        a[0] = x; a[1] = y; a[2] = x + w; a[3] = y + h
    }

    internal fun registerBar(edge: Float, rail: Boolean) {
        bar[0] = edge; bar[1] = if (rail) 1f else 0f
    }

    internal fun icon(t: Tab): FloatArray = icons[t.ordinal]
    internal fun barEdge(): Float = bar[0]
    internal fun isRail(): Boolean = bar[1] == 1f

    fun lift(t: Tab, scope: CoroutineScope, still: Boolean) {
        if (still || icons[t.ordinal][0].isNaN() || bar[0].isNaN()) return
        job?.cancel()
        tab = t
        job = scope.launch {
            try {
                progress.snapTo(0f)
                if (Graph.liteFx) {
                    progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 900f))
                    progress.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 700f))
                } else {
                    progress.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 420f))
                    delay(60)
                    progress.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 330f))
                }
            } finally { if (tab == t) tab = null }
        }
    }

    internal fun inBarAlpha(t: Tab): Float {
        if (tab != t) return 1f
        val p = progress.value
        return (1f - p * 3f).coerceIn(0f, 1f)
    }
}

fun Modifier.liftSource(tab: Tab, selected: Boolean, accent: Color, dotAlpha: () -> Float = { 1f }, register: Boolean = true): Modifier = this
    .then(
        if (register) Modifier.onGloballyPositioned { c ->
            val p = c.positionInWindow()
            TabLift.registerIcon(tab, p.x, p.y, c.size.width.toFloat(), c.size.height.toFloat())
        } else Modifier
    )
    .graphicsLayer { alpha = TabLift.inBarAlpha(tab) }
    .drawWithCache {
        val r = size.minDimension * 1.25f
        val c = Offset(size.width / 2f, size.height / 2f)
        val glow = Brush.radialGradient(listOf(accent.copy(alpha = 0.20f), Color.Transparent), c, r)
        val dotR = 2.dp.toPx()
        val dotAt = Offset(size.width / 2f, size.height + 5.dp.toPx())
        onDrawWithContent {
            if (selected) drawCircle(glow, r, c)
            drawContent()
            if (selected) {
                val a = dotAlpha()
                if (a > 0.01f) drawCircle(accent, dotR, dotAt, alpha = a)
            }
        }
    }

fun Modifier.liftBar(rail: Boolean = false): Modifier = this.onGloballyPositioned { c ->
    val p = c.positionInWindow()
    TabLift.registerBar(if (rail) p.x + c.size.width else p.y, rail)
}

@Composable
fun TabLiftOverlay(accent: Color) {
    val t = TabLift.tab ?: return
    val lite = Graph.liteFx
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val frost = lerp(surface, accent, 0.14f).copy(alpha = 0.86f)
    val rim = Color.White.copy(alpha = 0.32f)
    val density = LocalDensity.current
    val radiusPx = with(density) { 24.dp.toPx() }
    val liftPx = with(density) { (if (lite) 16.dp else 44.dp).toPx() }
    val snapPx = with(density) { 26.dp.toPx() }
    val geo = remember { FloatArray(6) }

    fun compute(): Boolean {
        val ic = TabLift.icon(t); val edge = TabLift.barEdge()
        if (ic[0].isNaN() || edge.isNaN()) return false
        val cx = (ic[0] + ic[2]) / 2f - TabLift.origin[0]; val cy = (ic[1] + ic[3]) / 2f - TabLift.origin[1]
        val p = TabLift.progress.value
        if (TabLift.isRail()) {
            val ex = edge - TabLift.origin[0]
            geo[0] = cx + (ex + liftPx - cx) * p; geo[1] = cy; geo[2] = ex; geo[3] = cy; geo[4] = 1f; geo[5] = 0f
        } else {
            val ey = edge - TabLift.origin[1]
            geo[0] = cx; geo[1] = cy + (ey - liftPx - cy) * p; geo[2] = cx; geo[3] = ey; geo[4] = 0f; geo[5] = -1f
        }
        return true
    }

    Box(
        Modifier.fillMaxSize().onGloballyPositioned { c ->
            val p = c.positionInWindow()
            TabLift.origin[0] = p.x; TabLift.origin[1] = p.y
        }
    ) {
        if (!lite) {
            Box(
                Modifier.fillMaxSize().drawWithCache {
                    val path = Path()
                    onDrawBehind {
                        if (!compute()) return@onDrawBehind
                        val bx = geo[0]; val by = geo[1]; val ex = geo[2]; val ey = geo[3]; val ax = geo[4]; val ay = geo[5]
                        val px = -ay; val py = ax
                        val gap = (bx - ex) * ax + (by - ey) * ay - radiusPx
                        if (gap > snapPx || gap < -radiusPx * 1.6f) return@onDrawBehind
                        val k = (gap / snapPx).coerceIn(0f, 1f)
                        val w0 = radiusPx * (1.05f - 0.55f * k)
                        val waist = radiusPx * (0.62f - 0.5f * k)
                        val c = 0.64f; val s = 0.77f
                        val a1x = bx - ax * radiusPx * c + px * radiusPx * s; val a1y = by - ay * radiusPx * c + py * radiusPx * s
                        val a2x = bx - ax * radiusPx * c - px * radiusPx * s; val a2y = by - ay * radiusPx * c - py * radiusPx * s
                        val mid = (gap * 0.5f).coerceAtLeast(0f)
                        path.rewind()
                        path.moveTo(ex + px * w0, ey + py * w0)
                        path.cubicTo(ex + px * waist + ax * mid, ey + py * waist + ay * mid, a1x - ax * mid * 0.6f, a1y - ay * mid * 0.6f, a1x, a1y)
                        path.lineTo(a2x, a2y)
                        path.cubicTo(a2x - ax * mid * 0.6f, a2y - ay * mid * 0.6f, ex - px * waist + ax * mid, ey - py * waist + ay * mid, ex - px * w0, ey - py * w0)
                        path.close()
                        drawPath(path, frost)
                    }
                }
            )
        }
        val glassMod = Modifier.glass(CircleShape, tint = lerp(surface, accent, 0.12f))
        Box(
            Modifier.layout { m, _ ->
                val d = (radiusPx * 2).roundToInt()
                val pl = m.measure(Constraints.fixed(d, d))
                layout(0, 0) { if (compute()) pl.place((geo[0] - radiusPx).roundToInt(), (geo[1] - radiusPx).roundToInt()) }
            }.graphicsLayer {
                val p = TabLift.progress.value
                val sc = 0.8f + 0.25f * p.coerceIn(0f, 1.2f)
                scaleX = sc; scaleY = sc; alpha = (p * 4f).coerceIn(0f, 1f)
                shadowElevation = 10.dp.toPx() * p.coerceIn(0f, 1f); shape = CircleShape; clip = false
            }.then(glassMod),
            contentAlignment = Alignment.Center,
        ) {
            Icon(t.selectedIcon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
