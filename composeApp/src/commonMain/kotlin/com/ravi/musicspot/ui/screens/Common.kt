package com.ravi.musicspot.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.currentTimeMillis
import com.ravi.musicspot.ui.Navigator
import kotlinx.coroutines.CancellationException

val LocalBottomInset = compositionLocalOf { 0.dp }
val LocalNav = staticCompositionLocalOf<Navigator> { error("no navigator") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val data: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val retry: () -> Unit)

@Composable
fun <T> rememberLoad(vararg key: Any?, block: suspend () -> T): Loader<T> {
    var attempt by remember { mutableIntStateOf(0) }
    val cacheKey = remember(*key) { key.joinToString("|") { it.toString() } }
    @Suppress("UNCHECKED_CAST")
    var state by remember(*key) { mutableStateOf((LoadCache.get(cacheKey) as? Load<T>) ?: Load.Loading) }
    val online by Graph.online.collectAsState()
    LaunchedEffect(*key, attempt) {
        if (attempt == 0 && state is Load.Ok && LoadCache.fresh(cacheKey)) return@LaunchedEffect
        if (state !is Load.Ok) state = Load.Loading
        state = try {
            Load.Ok(block()).also { LoadCache.put(cacheKey, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state is Load.Ok) state else Load.Failed(e.message ?: "Failed to load")
        }
    }
    LaunchedEffect(online) { if (online && state is Load.Failed) attempt++ }
    return Loader(state) { attempt++ }
}

@Composable
fun pagePadding(top: Dp = 0.dp, header: Dp = 0.dp): PaddingValues {
    val sb = WindowInsets.statusBars.asPaddingValues()
    val dir = LocalLayoutDirection.current
    val t = maxOf(sb.calculateTopPadding() + top, if (header > 0.dp) header + 8.dp else 0.dp)
    return PaddingValues(start = sb.calculateStartPadding(dir), end = sb.calculateEndPadding(dir), top = t, bottom = LocalBottomInset.current)
}

@Composable
fun rememberHeaderHeight() = remember { mutableStateOf(0.dp) }

@Suppress("unused")
@Composable
private fun Unused() = Box {}

object LoadCache {
    private const val MAX = 48
    private const val FRESH_MS = 10 * 60_000L
    private val map = mutableMapOf<String, Pair<Load<*>, Long>>()

    fun get(k: String): Load<*>? = map[k]?.first

    fun fresh(k: String): Boolean = map[k]?.let { currentTimeMillis() - it.second < FRESH_MS } == true

    fun put(k: String, v: Load<*>) {
        if (map.size >= MAX) {
            val oldest = map.keys.firstOrNull()
            if (oldest != null) map.remove(oldest)
        }
        map[k] = v to currentTimeMillis()
    }
}
