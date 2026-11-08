package com.ravi.musicspot.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

object FormatUtils {
    fun formatDuration(totalSeconds: Long): String {
        val s = if (totalSeconds < 0) 0 else totalSeconds
        val m = s / 60
        val sec = s % 60
        return "$m:${sec.toString().padStart(2, '0')}"
    }

    fun formatGrouped(value: Long): String {
        val s = value.toString()
        val isNegative = s.startsWith("-")
        val digits = if (isNegative) s.substring(1) else s
        val sb = StringBuilder()
        for (i in digits.indices) {
            if (i > 0 && (digits.length - i) % 3 == 0) {
                sb.append(',')
            }
            sb.append(digits[i])
        }
        return if (isNegative) "-$sb" else sb.toString()
    }

    fun formatDecimals(value: Double, decimals: Int, showSign: Boolean = false): String {
        val sign = when {
            value < 0 -> "-"
            showSign -> "+"
            else -> ""
        }
        val factor = 10.0.pow(decimals)
        val rounded = (abs(value) * factor).roundToLong()
        val intPart = rounded / factor.toLong()
        val fracPart = rounded % factor.toLong()
        return if (decimals > 0) {
            "$sign$intPart.${fracPart.toString().padStart(decimals, '0')}"
        } else {
            "$sign$intPart"
        }
    }
}
