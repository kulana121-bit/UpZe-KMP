package com.ravi.musicspot.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-platform in-memory playback diagnostics ring buffer (last [MAX] lines).
 * Every line is passed through [redact] (cookies, tokens, signatures, query strings removed).
 */
object PlaybackLog {
    const val MAX = 300
    private val lines = ArrayList<String>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    class Attempt(val tag: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Attempt>
        private val steps = LinkedHashMap<String, String>()
        fun step(source: String, what: String) { steps[source] = what }
        fun summary(): String = steps.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
    }

    private val summaries = LinkedHashMap<String, String>()

    fun add(msg: String, tag: String? = null) {
        val line = "${tag?.let { "[$it] " } ?: ""}${redact(msg)}".take(600)
        lines.add(line)
        while (lines.size > MAX) lines.removeAt(0)
        _version.value = _version.value + 1
    }

    suspend fun log(msg: String) = add(msg, coroutineContext[Attempt]?.tag)
    suspend fun step(source: String, what: String) { coroutineContext[Attempt]?.step(source, what) }
    suspend fun attempt(): Attempt? = coroutineContext[Attempt]

    fun remember(trackId: String, summary: String) { if (summary.isNotBlank()) summaries[trackId] = summary }
    fun forget(trackId: String) { summaries.remove(trackId) }
    fun summaryFor(trackId: String?): String? = trackId?.let { summaries[it] }

    fun text(): String = lines.joinToString("\n")
    fun size(): Int = lines.size
    fun clear() {
        lines.clear()
        _version.value = _version.value + 1
    }

    private val URL = Regex("""https?://[^\s"'<>]+""")
    private val SECRET = Regex("""(?i)\b(cookie|set-cookie|authorization|sapisid|__secure-[0-9a-z]+|sp_dc|x-goog-[a-z-]+|visitor_?data|token|access_token|key|sig|signature|pot|n)\s*[=:]\s*[^\s;,&"]+""")

    /** Cuts URLs to host + path and blanks cookie/token-looking key=value pairs. Pure; unit-tested. */
    fun redact(s: String): String {
        val noUrls = URL.replace(s) { m ->
            val u = m.value
            val rest = u.substringAfter("://")
            val hostPath = rest.substringBefore('?').substringBefore('#')
            "${u.substringBefore("://")}://${hostPath.take(80)}${if ('?' in rest) "?…" else ""}"
        }
        return SECRET.replace(noUrls) { "${it.groupValues[1]}=<redacted>" }
    }
}
