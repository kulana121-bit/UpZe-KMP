package com.ravi.musicspot.core

expect fun openPlatformUrl(url: String): Boolean

object Links {
    const val GITHUB = "https://github.com/kulana121-bit/UpZe-music"
    const val INSTAGRAM = "https://www.instagram.com/jus7_rv/"

    fun open(url: String): Boolean = openPlatformUrl(url)
}
