package com.ravi.musicspot.core

expect fun platformCurrentTimeMillis(): Long
expect fun platformTimeZoneOffsetMs(ms: Long): Long

fun currentTimeMillis(): Long = platformCurrentTimeMillis()
fun timeZoneOffsetMs(ms: Long = currentTimeMillis()): Long = platformTimeZoneOffsetMs(ms)
