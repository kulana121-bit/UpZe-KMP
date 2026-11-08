package com.ravi.musicspot.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.data.GlassStyle
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** The content layer floating glass samples. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun glassStyle(): GlassStyle {
    val s by Graph.settings.state.collectAsState()
    return effectiveGlass(s.glass)
}

internal fun effectiveGlass(chosen: GlassStyle, lowEnd: Boolean = Graph.lowEnd, liteFx: Boolean = Graph.liteFx): GlassStyle = when {
    lowEnd -> GlassStyle.SOLID
    liteFx && (chosen == GlassStyle.LIQUID || chosen == GlassStyle.FROSTED) -> GlassStyle.WATER
    else -> chosen
}

@Composable
fun blurIntensity(): Float {
    val s by Graph.settings.state.collectAsState()
    return s.blurIntensity.coerceIn(0f, 1f)
}

private fun radius(k: Float, min: Float, max: Float) = min + (max - min) * k

private val edgeBrush = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.32f),
    0.45f to Color.White.copy(alpha = 0.06f),
    1f to Color.White.copy(alpha = 0.20f),
)

@Composable
fun Modifier.glass(shape: Shape, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh, strong: Boolean = false): Modifier {
    val s by Graph.settings.state.collectAsState()
    val style = effectiveGlass(s.glass)
    if (style == GlassStyle.WATER) {
        return HazeGlass.water(this, shape, tint, strong, MaterialTheme.colorScheme.background, com.ravi.musicspot.ui.theme.LocalSongTint.current)
    }
    val k = s.blurIntensity.coerceIn(0f, 1f)
    if (k > 0.02f && style != GlassStyle.SOLID) {
        val haze = LocalHazeState.current
        if (haze != null) {
            return HazeGlass.haze(this, haze, shape, tint, MaterialTheme.colorScheme.background, edgeBrush, k)
        }
    }
    return this.clip(shape).background(tint.copy(alpha = 0.94f)).border(0.75.dp, edgeBrush, shape)
}

@Composable
fun Modifier.glassShadow(elevation: Dp, shape: Shape): Modifier =
    if (glassStyle() == GlassStyle.WATER) this
    else this.then(Modifier.shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = Color.Black.copy(alpha = 0.40f)))

@Composable
fun glassIsClear(): Boolean = glassStyle() == GlassStyle.WATER

private object HazeGlass {
    @OptIn(ExperimentalHazeApi::class)
    @Composable
    fun haze(m: Modifier, haze: HazeState, shape: Shape, tint: Color, background: Color, edge: Brush, k: Float): Modifier {
        val film = tint.copy(alpha = (minOf(tint.alpha, 0.7f).coerceAtLeast(0.55f) + (1f - k) * 0.2f).coerceAtMost(0.9f))
        val scale = if (Graph.liteFx) 0.25f else 0.33f
        val style = remember(background, film, k) {
            HazeStyle(backgroundColor = background, tint = HazeTint(film), blurRadius = radius(k, 8f, 32f).dp, noiseFactor = 0f)
        }
        return m.clip(shape)
            .hazeEffect(state = haze, style = style) {
                blurEnabled = true
                inputScale = HazeInputScale.Fixed(scale)
            }
            .border(0.75.dp, edge, shape)
    }

    @Composable
    fun water(m: Modifier, shape: Shape, tint: Color, strong: Boolean, background: Color, song: Color?): Modifier {
        val dark = (background.red + background.green + background.blue) / 3f < 0.5f
        val wash = song ?: tint
        val rim = remember(dark, wash) {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (dark) 0.40f else 0.85f),
                    Color.White.copy(alpha = if (dark) 0.06f else 0.20f),
                    wash.copy(alpha = 0.18f)
                )
            )
        }
        val films = remember(background, wash, strong) {
            val film = lerp(background, wash, if (dark) 0.16f else 0.10f)
            val fill = Brush.verticalGradient(
                listOf(
                    film.copy(alpha = if (strong) 0.70f else 0.58f),
                    film.copy(alpha = if (strong) 0.62f else 0.50f),
                )
            )
            val sheen = if (dark) waterSheenDark else waterSheenLight
            Modifier.drawBehind {
                drawRect(fill)
                drawRect(sheen)
            }
        }
        return m.clip(shape).then(films).border(1.dp, rim, shape)
    }

    private val waterSheenDark = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.08f), 0.4f to Color.Transparent, 1f to Color.Transparent)
    private val waterSheenLight = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.4f to Color.Transparent, 1f to Color.Transparent)
}

@Composable
fun Modifier.glassSheet(shape: Shape): Modifier {
    val c = MaterialTheme.colorScheme
    if (glassStyle() == GlassStyle.WATER) {
        val wash = com.ravi.musicspot.ui.theme.LocalSongTint.current ?: c.primary
        val fill = remember(c.surfaceContainerHigh, wash) {
            val film = lerp(c.surfaceContainerHigh, wash, 0.10f)
            Brush.verticalGradient(listOf(film.copy(alpha = 0.84f), c.surfaceContainerHigh.copy(alpha = 0.88f)))
        }
        return this.clip(shape).background(fill).border(1.dp, sheetRimWater, shape)
    }
    val fill = remember(c.surfaceContainerHighest, c.surfaceContainerHigh) {
        Brush.verticalGradient(listOf(lerp(c.surfaceContainerHighest, Color.White, 0.04f).copy(alpha = 0.96f), c.surfaceContainerHigh.copy(alpha = 0.97f)))
    }
    return this.clip(shape).background(fill).border(0.75.dp, sheetRim, shape)
}

private val sheetRimWater = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f)))
private val sheetRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent))

@Composable
fun GlassLayer(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.background,
    overlay: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val haze = rememberHazeState()
    val s by Graph.settings.state.collectAsState()
    val sampled = s.glass != GlassStyle.SOLID && s.glass != GlassStyle.WATER && s.blurIntensity > 0.02f
    Box(modifier) {
        val src = if (sampled) Modifier.fillMaxSize().background(background).hazeSource(haze) else Modifier.fillMaxSize().background(background)
        Box(src, content = content)
        CompositionLocalProvider(LocalHazeState provides if (sampled) haze else null) {
            overlay()
        }
    }
}

@Composable
fun Modifier.liquidGlass(shape: Shape, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Modifier {
    val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    if (glassStyle() == GlassStyle.WATER) return this.glass(shape, tint = tint, strong = true)
    return this.glass(shape, tint = tint, strong = true)
        .clip(shape)
        .background(if (dark) liquidSheenDark else liquidSheenLight)
        .border(1.dp, if (dark) liquidRimDark else liquidRimLight, shape)
}

private val liquidSheenDark = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.45f to Color.Transparent, 1f to Color.Transparent)
private val liquidSheenLight = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.45f to Color.Transparent, 1f to Color.Transparent)
private val liquidRimDark = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f)))
private val liquidRimLight = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.35f)))
