package com.ravi.musicspot.core

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openPlatformUrl(url: String): Boolean = runCatching {
    val nsUrl = NSURL.URLWithString(url) ?: return false
    UIApplication.sharedApplication.openURL(nsUrl)
}.getOrDefault(false)
