package com.ravi.musicspot.core

expect object PlatformActions {
    fun shareText(text: String, title: String? = null)
    fun copyText(text: String, label: String = "Copied")
    fun pickBackupFile(onFileRead: (String?) -> Unit)
    fun saveBackupFile(suggestedName: String, content: String, onSaved: (Boolean) -> Unit)
    fun openAppSettings()
    fun openUrl(url: String)
}
