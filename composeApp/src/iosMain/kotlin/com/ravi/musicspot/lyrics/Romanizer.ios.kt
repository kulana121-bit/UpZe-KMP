package com.ravi.musicspot.lyrics

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFMutableStringRef
import platform.CoreFoundation.CFStringTransform
import platform.CoreFoundation.kCFStringTransformStripCombiningMarks
import platform.CoreFoundation.kCFStringTransformToLatin
import platform.Foundation.NSMutableString

actual fun isPlatformTransliterateAvailable(): Boolean = true

@OptIn(ExperimentalForeignApi::class)
actual fun platformTransliterate(text: String, isHan: Boolean): String? {
    val mutable = NSMutableString(string = text)
    val cfStr = mutable as CFMutableStringRef
    CFStringTransform(cfStr, null, kCFStringTransformToLatin, false)
    CFStringTransform(cfStr, null, kCFStringTransformStripCombiningMarks, false)
    return mutable as String
}
