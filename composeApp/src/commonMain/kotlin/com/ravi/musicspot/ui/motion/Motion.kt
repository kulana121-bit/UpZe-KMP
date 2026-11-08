package com.ravi.musicspot.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph

@Composable
fun reducedMotion(): Boolean {
    val s by Graph.settings.state.collectAsState()
    return s.reduceMotion || Graph.lowEnd
}

class Haptics(private val hapticFeedback: HapticFeedback?, private val on: () -> Boolean) {
    fun tick() { if (on()) hapticFeedback?.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    fun click() { if (on()) hapticFeedback?.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    fun confirm() { if (on()) hapticFeedback?.performHapticFeedback(HapticFeedbackType.LongPress) }
}

@Composable
fun rememberHaptics(): Haptics {
    val hf = LocalHapticFeedback.current
    return remember(hf) { Haptics(hf) { Graph.settings.value.haptics } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    onClickLabel: String? = null,
    pressedScale: Float = 0.96f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val still = reducedMotion()
    val scale by animateFloatAsState(
        if (pressed && !still) pressedScale else 1f,
        spring(dampingRatio = 0.55f, stiffness = 306f),
        label = "press"
    )
    val h = rememberHaptics()
    val layer = this.graphicsLayer { scaleX = scale; scaleY = scale }
    return if (onLongClick == null) {
        layer.clickable(interactionSource = interaction, indication = ripple(), onClickLabel = onClickLabel) {
            h.tick()
            onClick()
        }
    } else {
        layer.combinedClickable(
            interactionSource = interaction,
            indication = ripple(),
            onClickLabel = onClickLabel,
            onLongClickLabel = "Song options",
            onLongClick = { h.confirm(); onLongClick() }
        ) {
            h.tick()
            onClick()
        }
    }
}

@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, scale: Float = 0.96f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val still = reducedMotion()
    val s by animateFloatAsState(
        if (pressed && !still) scale else 1f,
        spring(dampingRatio = 0.55f, stiffness = 306f),
        label = "pressScale"
    )
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

object MotionSpec {
    val emphasized = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasizedDecelerate = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT = 210
    const val MEDIUM = 420
    const val EXIT = 180

    const val SURFACE_DAMPING = 0.82f
    const val SURFACE_STIFFNESS = 280f

    fun <T> surfaceSpring(threshold: T? = null) = spring(
        dampingRatio = SURFACE_DAMPING,
        stiffness = SURFACE_STIFFNESS,
        visibilityThreshold = threshold
    )

    fun <T> popSpring() = spring<T>(dampingRatio = 0.65f, stiffness = 340f)

    fun <T> exitTween(duration: Int = EXIT) = tween<T>(duration, easing = emphasizedAccelerate)

    fun scrimIn(still: Boolean): EnterTransition = if (still) EnterTransition.None else fadeIn(tween(MEDIUM, easing = LinearEasing))
    fun scrimOut(still: Boolean): ExitTransition = if (still) ExitTransition.None else fadeOut(tween(EXIT, easing = LinearEasing))

    fun sheetIn(still: Boolean): EnterTransition = if (still) EnterTransition.None else
        slideInVertically(surfaceSpring(IntOffset.VisibilityThreshold)) { it } + fadeIn(tween(MEDIUM, easing = LinearEasing))

    fun sheetOut(still: Boolean): ExitTransition = if (still) ExitTransition.None else
        slideOutVertically(exitTween()) { it } + fadeOut(tween(SHORT, easing = LinearEasing))

    fun dialogIn(still: Boolean): EnterTransition = if (still) EnterTransition.None else
        scaleIn(surfaceSpring(), initialScale = 0.88f) + fadeIn(tween(SHORT, easing = LinearEasing))

    fun dialogOut(still: Boolean): ExitTransition = if (still) ExitTransition.None else
        scaleOut(exitTween(), targetScale = 0.88f) + fadeOut(tween(SHORT, easing = LinearEasing))

    fun menuIn(still: Boolean, origin: TransformOrigin): EnterTransition = if (still) EnterTransition.None else
        scaleIn(popSpring(), initialScale = 0.8f, transformOrigin = origin) + fadeIn(tween(SHORT, easing = LinearEasing))

    fun menuOut(still: Boolean, origin: TransformOrigin): ExitTransition = if (still) ExitTransition.None else
        scaleOut(tween(SHORT, easing = emphasizedAccelerate), targetScale = 0.8f, transformOrigin = origin) + fadeOut(tween(SHORT, easing = LinearEasing))

    fun fadeThrough(still: Boolean): ContentTransform = if (still) EnterTransition.None togetherWith ExitTransition.None else
        (fadeIn(tween(294, delayMillis = 126, easing = emphasized)) + scaleIn(tween(294, delayMillis = 126, easing = emphasized), initialScale = 0.92f)) togetherWith fadeOut(tween(126))

    fun sharedAxisX(forward: Boolean, still: Boolean): ContentTransform {
        if (still) return fadeIn(tween(196)) togetherWith fadeOut(tween(140))
        val spec = tween<IntOffset>(MEDIUM, easing = emphasized)
        val d = if (forward) 1 else -1
        return (slideInHorizontally(spec) { d * (it * 0.08f).toInt() } + fadeIn(tween(294, delayMillis = 84))) togetherWith
            (slideOutHorizontally(spec) { -d * (it * 0.08f).toInt() } + fadeOut(tween(126)))
    }

    fun sharedAxisY(up: Boolean, still: Boolean): ContentTransform {
        if (still) return EnterTransition.None togetherWith ExitTransition.None
        val d = if (up) 1 else -1
        return (slideInVertically(surfaceSpring(IntOffset.VisibilityThreshold)) { d * it / 5 } + fadeIn(tween(294, delayMillis = 84))) togetherWith
            (slideOutVertically(exitTween()) { -d * it / 8 } + fadeOut(tween(126)))
    }

    fun containerGrow(still: Boolean, origin: TransformOrigin): ContentTransform {
        if (still) return fadeIn(tween(196)) togetherWith fadeOut(tween(140))
        if (Graph.liteFx) return fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(90))
        return (scaleIn(tween(MEDIUM, easing = emphasizedDecelerate), initialScale = 0.86f, transformOrigin = origin) +
            fadeIn(tween(210, delayMillis = 30, easing = LinearEasing))) togetherWith
            (scaleOut(tween(MEDIUM, easing = emphasized), targetScale = 0.96f) + fadeOut(tween(168, delayMillis = 60)))
    }

    fun containerShrink(still: Boolean, origin: TransformOrigin): ContentTransform {
        if (still) return fadeIn(tween(196)) togetherWith fadeOut(tween(140))
        if (Graph.liteFx) return fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(90))
        return ((scaleIn(tween(MEDIUM, easing = emphasizedDecelerate), initialScale = 0.96f) + fadeIn(tween(210, easing = LinearEasing))) togetherWith
            (scaleOut(tween(EXIT, easing = emphasizedAccelerate), targetScale = 0.86f, transformOrigin = origin) + fadeOut(tween(EXIT, delayMillis = 40, easing = LinearEasing))))
            .apply { targetContentZIndex = -1f }
    }

    fun containerRise(open: Boolean, still: Boolean): ContentTransform {
        if (still) return EnterTransition.None togetherWith ExitTransition.None
        val bottom = TransformOrigin(0.5f, 1f); val top = TransformOrigin(0.5f, 0f)
        return if (open)
            (scaleIn(surfaceSpring(), initialScale = 0.88f, transformOrigin = bottom) + slideInVertically(surfaceSpring(IntOffset.VisibilityThreshold)) { it / 6 } +
                fadeIn(tween(308, delayMillis = 56, easing = emphasizedDecelerate))) togetherWith
                (scaleOut(tween(EXIT, easing = emphasizedAccelerate), targetScale = 0.92f, transformOrigin = top) + fadeOut(tween(168)))
        else
            (scaleIn(popSpring(), initialScale = 0.94f, transformOrigin = top) + fadeIn(tween(308, delayMillis = 84, easing = emphasizedDecelerate))) togetherWith
                (scaleOut(tween(EXIT, easing = emphasizedAccelerate), targetScale = 0.88f, transformOrigin = bottom) +
                    slideOutVertically(exitTween()) { it / 6 } + fadeOut(tween(224)))
    }

    fun dockSpring() = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 194f, visibilityThreshold = 0.001f)

    fun <T> containerSpring() = spring<T>(dampingRatio = 0.86f, stiffness = 194f)
}

object PageMotion {
    fun push(still: Boolean): ContentTransform = MotionSpec.sharedAxisX(forward = true, still = still)
    fun pop(still: Boolean): ContentTransform = MotionSpec.sharedAxisX(forward = false, still = still)
    fun pushFrom(still: Boolean, origin: TransformOrigin): ContentTransform = MotionSpec.containerGrow(still, origin)
    fun popTo(still: Boolean, origin: TransformOrigin): ContentTransform = MotionSpec.containerShrink(still, origin)
    fun tab(still: Boolean): ContentTransform = if (still) fadeIn(tween(168)) togetherWith fadeOut(tween(126)) else MotionSpec.fadeThrough(false)
    val playerSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 214f)
}

@Composable
fun Modifier.staggerIn(index: Int): Modifier {
    val still = reducedMotion()
    val a = remember { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!still) {
            kotlinx.coroutines.delay(index * 55L)
            a.animateTo(1f, tween(MotionSpec.MEDIUM + 140, easing = MotionSpec.emphasizedDecelerate))
        }
    }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 24.dp.toPx() }
}

fun iconSwap(still: Boolean): ContentTransform = if (still) EnterTransition.None togetherWith ExitTransition.None else
    (scaleIn(MotionSpec.popSpring(), initialScale = 0.4f) + fadeIn(tween(MotionSpec.SHORT, delayMillis = 60))) togetherWith
        (scaleOut(tween(MotionSpec.SHORT, easing = MotionSpec.emphasizedAccelerate), targetScale = 0.4f) + fadeOut(tween(MotionSpec.SHORT)))
