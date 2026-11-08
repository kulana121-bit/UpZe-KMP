package com.ravi.musicspot.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.ravi.musicspot.ui.Pills

actual object PlatformActions {
    private var context: Context? = null
    var onPickFileCallback: ((String?) -> Unit)? = null
    var onSaveFileCallback: ((Boolean) -> Unit)? = null
    var pendingSaveContent: String? = null
    var pendingSaveName: String? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    fun getAppContext(): Context? = context

    private fun getContext(): Context =
        requireNotNull(context) { "PlatformActions not initialized with context" }

    actual fun shareText(text: String, title: String?) {
        val ctx = getContext()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (title != null) putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, title ?: "Share").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }

    actual fun copyText(text: String, label: String) {
        val ctx = getContext()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("UpZe", text))
        Pills.show(label)
    }

    actual fun pickBackupFile(onFileRead: (String?) -> Unit) {
        onPickFileCallback = onFileRead
        // Can be handled by MainActivity launcher or fallback to reading from backup directory
        val backupDir = PlatformIO.cacheDir()
        val defaultFile = File(backupDir, "backup.json")
        if (defaultFile.exists()) {
            onFileRead(defaultFile.readText())
        } else {
            onFileRead(null)
        }
    }

    actual fun saveBackupFile(suggestedName: String, content: String, onSaved: (Boolean) -> Unit) {
        pendingSaveName = suggestedName
        pendingSaveContent = content
        onSaveFileCallback = onSaved
        val target = File(PlatformIO.cacheDir(), suggestedName)
        target.writeText(content)
        shareText(content, suggestedName)
        onSaved(true)
    }

    actual fun openAppSettings() {
        val ctx = getContext()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    actual fun openUrl(url: String) {
        val ctx = getContext()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
