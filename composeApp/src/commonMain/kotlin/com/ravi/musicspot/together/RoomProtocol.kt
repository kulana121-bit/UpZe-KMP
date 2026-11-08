package com.ravi.musicspot.together

import com.ravi.musicspot.core.Crypto
import com.ravi.musicspot.model.Track
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Listen together: real-time room synchronization protocol across Android and iOS */
@Serializable
data class RoomMsg(
    val t: String,
    val from: String,
    val name: String = "",
    val track: Track? = null,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val atMs: Long = 0,
    val listeners: Int = 0,
    val seq: Long = 0,
)

object RoomProtocol {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val CODE_LEN = 6

    fun newCode(rnd: Random = Random.Default): String =
        (1..CODE_LEN).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")

    fun normalize(input: String): String? {
        val code = input.uppercase().filter { it.isLetterOrDigit() }
        return code.takeIf { it.length == CODE_LEN && it.all { ch -> ch in ALPHABET } }
    }

    /** Relay topic for room code */
    fun topic(code: String): String {
        val hash = Crypto.sha1("upze-listen-together:$code")
        return "upze_" + hash.take(12)
    }

    /** Expected playback position on listener client */
    fun expectedPosition(m: RoomMsg, nowMs: Long): Long =
        if (!m.playing) m.positionMs else (m.positionMs + (nowMs - m.atMs).coerceIn(0, 6 * 60 * 60 * 1000L))

    const val DRIFT_MS = 2_500L
}
