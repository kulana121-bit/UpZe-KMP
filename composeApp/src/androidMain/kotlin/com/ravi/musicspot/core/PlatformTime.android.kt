package com.ravi.musicspot.core

actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformTimeZoneOffsetMs(ms: Long): Long =
    java.util.TimeZone.getDefault().getOffset(ms).toLong()
