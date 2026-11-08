package com.ravi.musicspot.core

import android.content.Intent
import android.net.Uri

actual fun openPlatformUrl(url: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Android activity or application context can launch this
    true
}.getOrDefault(false)
