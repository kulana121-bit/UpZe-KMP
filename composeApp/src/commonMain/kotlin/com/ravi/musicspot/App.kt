package com.ravi.musicspot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ravi.musicspot.ui.AppRoot
import com.ravi.musicspot.ui.motion.trackTaps
import com.ravi.musicspot.ui.setup.SetupFlow
import com.ravi.musicspot.ui.theme.MusicSpotTheme
import kotlinx.coroutines.flow.combine

@Composable
fun App() {
    var openPlayer by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().trackTaps()) {
        val gate by remember {
            combine(Graph.ready, Graph.settings.state) { r, st -> r to st }
        }.collectAsState(initial = Graph.ready.value to Graph.settings.state.value)
        val (ready, s) = gate
        if (!ready) {
            MusicSpotTheme(artUrl = null, dynamic = s.dynamicColor, mode = s.themeMode) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
        } else if (s.onboarded && !s.setupDone) {
            MusicSpotTheme(artUrl = null, dynamic = s.dynamicColor, mode = s.themeMode) {
                SetupFlow()
            }
        } else {
            AppRoot(openPlayer = openPlayer, onOpenPlayerConsumed = { openPlayer = false })
        }
    }
}
