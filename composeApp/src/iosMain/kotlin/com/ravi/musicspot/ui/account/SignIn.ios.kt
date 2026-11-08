package com.ravi.musicspot.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.account.Sessions
import com.ravi.musicspot.ui.Pills
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.*

@Composable
actual fun GoogleLoginScreen(onDone: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Google Sign-In is configured via web account settings.")
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun SpotifyLoginScreen(onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().padding(8.dp)) {
            IconButton(onClick = { onDone(false) }, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            Text("Sign in to Spotify", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
        }

        if (loading && !saving) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (saving) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            UIKitView(
                factory = {
                    val config = WKWebViewConfiguration()
                    val webView = WKWebView(frame = platform.CoreGraphics.CGRectZero.readValue(), configuration = config)
                    val url = NSURL.URLWithString("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
                    if (url != null) {
                        webView.loadRequest(NSURLRequest.requestWithURL(url))
                    }
                    webView.navigationDelegate = object : platform.darwin.NSObject(), WKNavigationDelegateProtocol {
                        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                            loading = false
                            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
                                val list = cookies?.filterIsInstance<platform.Foundation.NSHTTPCookie>() ?: emptyList()
                                val spDc = list.firstOrNull { it.name == "sp_dc" }?.value
                                if (!spDc.isNullOrBlank() && !saving) {
                                    saving = true
                                    scope.launch {
                                        runCatching {
                                            Sessions.signInSpotify(spDc)
                                        }.onSuccess { name ->
                                            Pills.show(if (name != null) "Spotify connected as $name" else "Spotify connected")
                                            onDone(true)
                                        }.onFailure {
                                            saving = false
                                            Pills.show("Sign in failed: ${it.message}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    webView
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
