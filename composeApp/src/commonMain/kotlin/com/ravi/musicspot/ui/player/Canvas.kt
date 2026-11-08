package com.ravi.musicspot.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.ravi.musicspot.Graph
import com.ravi.musicspot.account.Account
import com.ravi.musicspot.model.Track

@Composable
fun rememberCanvasUrl(t: Track): String? {
    val accounts by Graph.accounts.accounts.collectAsState()
    val cookie = accounts.firstOrNull { it.service == Account.Service.SPOTIFY }?.secret
    val url by produceState<String?>(null, t.id, cookie) {
        value = null
        if (cookie != null) value = runCatching { Graph.spotify.canvasUrl(cookie, t) }.getOrNull()
    }
    return url
}

@Composable
expect fun CanvasVideo(url: String, modifier: Modifier = Modifier)
