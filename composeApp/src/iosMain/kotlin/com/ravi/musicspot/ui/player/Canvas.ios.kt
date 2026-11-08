package com.ravi.musicspot.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.*
import platform.Foundation.NSURL
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CanvasVideo(url: String, modifier: Modifier) {
    var ready by remember(url) { mutableStateOf(false) }
    val shown by animateFloatAsState(if (ready) 1f else 0f, tween(840), label = "canvasIn")

    val player = remember(url) {
        val nsUrl = NSURL.URLWithString(url) ?: return@remember null
        val item = AVPlayerItem(uRL = nsUrl)
        val p = AVPlayer(playerItem = item)
        p.volume = 0f
        p.actionAtItemEnd = AVPlayerActionAtItemEndNone
        p
    }

    DisposableEffect(player) {
        if (player != null) {
            val observer = NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = player.currentItem,
                queue = null
            ) { _ ->
                player.seekToTime(platform.CoreMedia.CMTimeMake(value = 0, timescale = 1))
                player.play()
            }
            player.play()
            ready = true
            onDispose {
                NSNotificationCenter.defaultCenter.removeObserver(observer)
                player.pause()
            }
        } else {
            onDispose {}
        }
    }

    if (player != null) {
        UIKitView(
            factory = {
                val container = UIView()
                val playerLayer = AVPlayerLayer.playerLayerWithPlayer(player)
                playerLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
                container.layer.addSublayer(playerLayer)
                container
            },
            modifier = modifier.graphicsLayer { alpha = shown },
            update = { view ->
                view.layer.sublayers?.firstOrNull()?.let { layer ->
                    (layer as? AVPlayerLayer)?.frame = view.bounds
                }
            }
        )
    }
}
