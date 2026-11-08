package com.ravi.musicspot.lyrics

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFStringCreateMutableCopy
import platform.CoreFoundation.CFStringGetCharacters
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringTransform
import platform.CoreFoundation.kCFStringTransformStripCombiningMarks
import platform.CoreFoundation.kCFStringTransformToLatin

actual fun isPlatformTransliterateAvailable(): Boolean = true

@OptIn(ExperimentalForeignApi::class)
actual fun platformTransliterate(text: String, isHan: Boolean): String? {
    val cfStr = CFStringCreateMutableCopy(null, 0, text as CFStringRef) ?: return null
    CFStringTransform(cfStr, null, kCFStringTransformToLatin, false)
    CFStringTransform(cfStr, null, kCFStringTransformStripCombiningMarks, false)
    val len = CFStringGetLength(cfStr).toInt()
    if (len == 0) return ""
    val chars = CharArray(len)
    chars.usePinned { pinned ->
        CFStringGetCharacters(cfStr, CFRangeMake(0, len.toLong()), pinned.addressOf(0).reinterpret())
    }
    return chars.concatToString()
}
