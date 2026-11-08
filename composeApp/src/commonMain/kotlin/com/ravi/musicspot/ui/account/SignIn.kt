package com.ravi.musicspot.ui.account

import androidx.compose.runtime.Composable

@Composable
expect fun GoogleLoginScreen(onDone: (Boolean) -> Unit)

@Composable
expect fun SpotifyLoginScreen(onDone: (Boolean) -> Unit)
