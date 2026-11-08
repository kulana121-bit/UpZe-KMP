package com.ravi.musicspot.ui.player

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@OptIn(UnstableApi::class)
@Composable
actual fun CanvasVideo(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    var ready by remember(url) { mutableStateOf(false) }
    val shown by animateFloatAsState(if (ready) 1f else 0f, tween(840), label = "canvasIn")
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            addListener(object : Player.Listener { override fun onRenderedFirstFrame() { ready = true } })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val size = remember(url) { IntArray(2) }
    val views = remember(url) { arrayOfNulls<TextureView>(1) }
    fun crop() {
        val tv = views[0] ?: return
        val vw = size[0]; val vh = size[1]
        if (vw <= 0 || vh <= 0 || tv.width <= 0 || tv.height <= 0) return
        val s = maxOf(tv.width / vw.toFloat(), tv.height / vh.toFloat())
        val m = android.graphics.Matrix()
        m.setScale(vw * s / tv.width, vh * s / tv.height, tv.width / 2f, tv.height / 2f)
        tv.setTransform(m)
    }
    DisposableEffect(player) {
        val l = object : Player.Listener { override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) { size[0] = v.width; size[1] = v.height; crop() } }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    AndroidView(
        factory = { c -> TextureView(c).also { tv ->
            views[0] = tv
            player.setVideoTextureView(tv)
            tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> crop() }
        } },
        modifier = modifier.graphicsLayer { alpha = shown },
    )
}
