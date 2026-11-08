package com.ravi.musicspot.core

import com.ravi.musicspot.ui.Pills
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIPasteboard

actual object PlatformActions {
    actual fun shareText(text: String, title: String?) {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
        val activity = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        root.presentViewController(activity, animated = true, completion = null)
    }

    actual fun copyText(text: String, label: String) {
        UIPasteboard.generalPasteboard.string = text
        Pills.show(label)
    }

    actual fun pickBackupFile(onFileRead: (String?) -> Unit) {
        val appDir = PlatformIO.appDir()
        val defaultFile = File(appDir, "backup.json")
        if (defaultFile.exists()) {
            onFileRead(defaultFile.readText())
        } else {
            onFileRead(null)
        }
    }

    actual fun saveBackupFile(suggestedName: String, content: String, onSaved: (Boolean) -> Unit) {
        val target = File(PlatformIO.appDir(), suggestedName)
        target.writeText(content)
        shareText(content, suggestedName)
        onSaved(true)
    }

    actual fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url)
    }

    actual fun openUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl)
    }
}
