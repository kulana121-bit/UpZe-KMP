package com.ravi.musicspot.core

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.defaultTimeZone
import platform.Foundation.timeIntervalSince1970

actual fun platformCurrentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun platformTimeZoneOffsetMs(ms: Long): Long {
    val date = NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)
    return NSTimeZone.defaultTimeZone.secondsFromGMTForDate(date).toLong() * 1000L
}
