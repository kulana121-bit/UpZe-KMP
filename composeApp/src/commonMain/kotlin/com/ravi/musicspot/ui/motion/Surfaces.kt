package com.ravi.musicspot.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ravi.musicspot.Graph
import kotlinx.coroutines.launch

@Composable
expect fun ConfigureOverlayDialogWindow()

@Composable
private fun <T : Any> rememberHeld(item: T?): Pair<T, MutableTransitionState<Boolean>>? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (item != null) holder[0] = item
    val vis = remember { MutableTransitionState(false) }
    vis.targetState = item != null
    if (!vis.targetState && vis.isIdle && !vis.currentState) { holder[0] = null; return null }
    @Suppress("UNCHECKED_CAST")
    val shown = (item ?: holder[0] as T?) ?: return null
    return shown to vis
}

@Composable
private fun OverlayWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        ConfigureOverlayDialogWindow()
        content()
    }
}

@Composable
fun <T : Any> MotionSheet(item: T?, onDismiss: () -> Unit, content: @Composable ColumnScope.(T) -> Unit) {
    val (shown, vis) = rememberHeld(item) ?: return
    val still = reducedMotion()
    val tap = remember(shown) { if (TapOrigin.freshTap() && !Graph.liteFx) TapOrigin.point() else null }
    FocusBlurWhile(vis.targetState)
    OverlayWindow(onDismiss) {
        AnimatedVisibility(vis, enter = EnterTransition.None, exit = ExitTransition.None) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .animateEnterExit(enter = MotionSpec.scrimIn(still), exit = MotionSpec.scrimOut(still))
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close") { onDismiss() }
                )
                var drag by remember { mutableFloatStateOf(0f) }
                val scope = rememberCoroutineScope()
                val density = LocalDensity.current
                val threshold = with(density) { 96.dp.toPx() }
                fun settle(velocity: Float) {
                    if (drag > threshold || velocity > 1400f) onDismiss()
                    else scope.launch { animate(drag, 0f, animationSpec = MotionSpec.surfaceSpring()) { v, _ -> drag = v } }
                }
                val connection = remember {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            if (available.y < 0f && drag > 0f) { val c = maxOf(available.y, -drag); drag += c; return Offset(0f, c) }
                            return Offset.Zero
                        }
                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (available.y > 0f && source == NestedScrollSource.UserInput) { drag += available.y; return Offset(0f, available.y) }
                            return Offset.Zero
                        }
                        override suspend fun onPreFling(available: Velocity): Velocity {
                            if (drag > 0f) { settle(available.y); return available }
                            return Velocity.Zero
                        }
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 48.dp)
                        .widthIn(max = 640.dp).fillMaxWidth()
                        .then(
                            if (tap != null && !still) Modifier.animateEnterExit(enter = fadeIn(tween(MotionSpec.SHORT)), exit = fadeOut(tween(MotionSpec.EXIT, delayMillis = 60))).growFrom(this@AnimatedVisibility, tap, 0.55f)
                            else Modifier.animateEnterExit(enter = MotionSpec.sheetIn(still), exit = MotionSpec.sheetOut(still))
                        )
                        .graphicsLayer { translationY = drag }
                        .nestedScroll(connection)
                        .draggable(rememberDraggableState { dy -> drag = (drag + dy).coerceAtLeast(0f) }, Orientation.Vertical, onDragStopped = { v -> settle(v) })
                        .pointerInput(Unit) { detectTapGestures { } },
                ) { content(shown) }
            }
        }
    }
}

@Composable
fun <T : Any> MotionDialog(item: T?, onDismiss: () -> Unit, content: @Composable (T) -> Unit) {
    val (shown, vis) = rememberHeld(item) ?: return
    val still = reducedMotion()
    val tap = remember(shown) { if (TapOrigin.freshTap() && !Graph.liteFx) TapOrigin.point() else null }
    FocusBlurWhile(vis.targetState)
    OverlayWindow(onDismiss) {
        AnimatedVisibility(vis, enter = EnterTransition.None, exit = ExitTransition.None) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().animateEnterExit(enter = MotionSpec.scrimIn(still), exit = MotionSpec.scrimOut(still))
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close") { onDismiss() }
                )
                Box(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 40.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        (if (tap != null && !still) Modifier.animateEnterExit(enter = fadeIn(tween(MotionSpec.SHORT)), exit = fadeOut(tween(MotionSpec.EXIT))).growFrom(this@AnimatedVisibility, tap, 0.7f)
                        else Modifier.animateEnterExit(enter = MotionSpec.dialogIn(still), exit = MotionSpec.dialogOut(still)))
                            .pointerInput(Unit) { detectTapGestures { } },
                    ) { content(shown) }
                }
            }
        }
    }
}

@Composable
fun AlertDialogCard(
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Surface(
        Modifier.widthIn(min = 280.dp, max = 560.dp),
        shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(Modifier.padding(24.dp)) {
            title?.let {
                CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) { Box(Modifier.padding(bottom = 16.dp)) { it() } }
                }
            }
            text?.let {
                CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides AlertDialogDefaults.textContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { Box(Modifier.weight(1f, fill = false).padding(bottom = 24.dp)) { it() } }
                }
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

@Composable
fun <T : Any> MotionAlertDialog(
    item: T?,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable (T) -> Unit,
    dismissButton: (@Composable (T) -> Unit)? = null,
    title: (@Composable (T) -> Unit)? = null,
    text: (@Composable (T) -> Unit)? = null,
) = MotionDialog(item, onDismissRequest) { t ->
    AlertDialogCard(
        confirmButton = { confirmButton(t) },
        dismissButton = dismissButton?.let { d -> { d(t) } },
        title = title?.let { c -> { c(t) } },
        text = text?.let { c -> { c(t) } },
    )
}

@Composable
fun MotionAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) = MotionAlertDialog(
    item = if (visible) Unit else null, onDismissRequest = onDismissRequest,
    confirmButton = { confirmButton() }, dismissButton = dismissButton?.let { d -> { _: Unit -> d() } },
    title = title?.let { c -> { _: Unit -> c() } }, text = text?.let { c -> { _: Unit -> c() } },
)

internal fun menuPlacement(anchor: IntRect, window: IntSize, popup: IntSize, ltr: Boolean, margin: Int): Pair<IntOffset, TransformOrigin> {
    val startX = if (ltr) anchor.left else anchor.right - popup.width
    val endX = if (ltr) anchor.right - popup.width else anchor.left
    val x = listOf(startX, endX).firstOrNull { it >= margin && it + popup.width <= window.width - margin }
        ?: (window.width - popup.width - margin).coerceAtLeast(margin).coerceAtMost(startX.coerceAtLeast(margin))
    val below = anchor.bottom
    val above = anchor.top - popup.height
    val y = when {
        below + popup.height <= window.height - margin -> below
        above >= margin -> above
        else -> (window.height - popup.height - margin).coerceAtLeast(margin)
    }
    val ox = if (popup.width <= 0) 0f else ((anchor.left + anchor.right) / 2f - x) / popup.width
    val oy = if (popup.height <= 0) 0f else ((anchor.top + anchor.bottom) / 2f - y) / popup.height
    return IntOffset(x, y) to TransformOrigin(ox.coerceIn(0f, 1f), oy.coerceIn(0f, 1f))
}

@Composable
fun MotionDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    FocusBlurWhile(expanded)
    val (_, vis) = rememberHeld(if (expanded) Unit else null) ?: return
    val still = reducedMotion()
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(TransformOrigin(0.5f, 0f)) }
    val provider = remember(density) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val (at, o) = menuPlacement(anchorBounds, windowSize, popupContentSize, layoutDirection == LayoutDirection.Ltr, with(density) { 8.dp.roundToPx() })
                origin = o
                return at
            }
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(vis, enter = MotionSpec.menuIn(still, origin), exit = MotionSpec.menuOut(still, origin)) {
            Surface(
                shape = MenuDefaults.shape, color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation, shadowElevation = MenuDefaults.ShadowElevation,
            ) {
                Column(modifier.padding(vertical = 8.dp).width(IntrinsicSize.Max).widthIn(min = 112.dp, max = 280.dp).verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

object FocusBlur { var count by mutableIntStateOf(0) }

@Composable
fun FocusBlurWhile(active: Boolean) {
    DisposableEffect(active) {
        if (active) { FocusBlur.count++; onDispose { FocusBlur.count = (FocusBlur.count - 1).coerceAtLeast(0) } } else onDispose { }
    }
}

@Composable
fun Modifier.focusBlur(): Modifier {
    val still = reducedMotion()
    val a by animateFloatAsState(
        if (FocusBlur.count > 0) 1f else 0f,
        if (still) snap() else tween(MotionSpec.MEDIUM, easing = MotionSpec.emphasizedDecelerate),
        label = "focusBlur"
    )
    if (a <= 0.001f) return this
    val blur = !Graph.lowEnd
    return this.graphicsLayer {
        val s = 1f - 0.035f * a; scaleX = s; scaleY = s
        if (blur) { val r = 26.dp.toPx() * a; if (r > 0.5f) renderEffect = BlurEffect(r, r, TileMode.Clamp) }
    }
}
