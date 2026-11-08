package com.ravi.musicspot.lyrics

import android.os.Build

actual fun isPlatformTransliterateAvailable(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

private val anyTransliterator by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
    } else null
}

private val hanTransliterator by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII") }.getOrNull()
    } else null
}

actual fun platformTransliterate(text: String, isHan: Boolean): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val transliterator = if (isHan) hanTransliterator else anyTransliterator
    return transliterator?.transliterate(text)
}
