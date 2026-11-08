package com.ravi.musicspot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ravi.musicspot.playback.AndroidAudioPlayer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidAudioPlayer.init(this)
        com.ravi.musicspot.core.PlatformActions.init(this)
        Graph.init()
        setContent {
            App()
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "extra_open_player"
    }
}
