package com.ravi.musicspot.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.ravi.musicspot.Graph
import com.ravi.musicspot.data.DownloadEntry
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.ui.Pills
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.motion.pressable
import com.ravi.musicspot.ui.motion.pressScale
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.rememberHaptics
import com.ravi.musicspot.ui.motion.sharedCover
import com.ravi.musicspot.ui.openSongMenu
import com.ravi.musicspot.ui.theme.Ms

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, shape: Shape = Ms.r12, contentDescription: String? = null) {
    BoxWithConstraints(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        var failed by remember(url) { mutableStateOf(false) }
        var loaded by remember(url) { mutableStateOf(false) }
        if (url == null || failed || !loaded) ArtPlaceholder()
        if (url != null && !failed) {
            val px = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }
            val bucket = artBucket(px).let { if (Graph.liteFx) minOf(it, 480) else it }
            val maxBucket = remember(url) { IntArray(1) }
            if (bucket > maxBucket[0]) maxBucket[0] = bucket
            val reqBucket = maxBucket[0]
            val model = remember(url, reqBucket) { sizedArtUrl(url, reqBucket) }
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
                onError = { failed = true },
            )
        }
    }
}

private fun artBucket(px: Int): Int = when { px <= 0 -> 0; px <= 120 -> 120; px <= 240 -> 240; px <= 480 -> 480; px <= 720 -> 720; else -> 1080 }

fun sizedArtUrl(url: String, px: Int): String {
    if (px <= 0) return url
    val bucket = when { px <= 160 -> 160; px <= 320 -> 320; px <= 544 -> 544; else -> if (Graph.liteFx) 544 else 1080 }
    return when {
        ("googleusercontent.com" in url || "ggpht.com" in url) && GoogleSizeRe.containsMatchIn(url) ->
            url.replace(GoogleSizeRe, "=w$bucket-h$bucket")
        "saavncdn.com" in url && SaavnSizeRe.containsMatchIn(url) ->
            url.replace(SaavnSizeRe, if (bucket <= 160) "150x150" else "500x500")
        else -> url
    }
}

private val GoogleSizeRe = Regex("=w\\d+-h\\d+")
private val SaavnSizeRe = Regex("\\d+x\\d+")

@Composable
private fun ArtPlaceholder() {
    val a = MaterialTheme.colorScheme.surfaceContainerHighest
    val b = MaterialTheme.colorScheme.surfaceContainerHigh
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    val brush = remember(a, b) { Brush.linearGradient(listOf(a, b)) }
    val tint = remember(onVar) { onVar.copy(alpha = 0.6f) }
    Box(Modifier.fillMaxSize().background(brush), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = tint, modifier = Modifier.size(28.dp))
    }
}

fun formatTime(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0) / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) {
        val whole = kb.toLong()
        return "$whole KB"
    }
    val mb = kb / 1024.0
    if (mb < 1024) {
        val whole = mb.toLong()
        val frac = ((mb - whole) * 10).toLong()
        return "$whole.$frac MB"
    }
    val gb = mb / 1024.0
    val whole = gb.toLong()
    val frac = ((gb - whole) * 100).toLong().toString().padStart(2, '0')
    return "$whole.$frac GB"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    t: Track,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
    playing: Boolean = false,
    index: Int? = null,
    showArt: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onLongPress: (() -> Unit)? = { openSongMenu(t) },
) {
    val rowHaptics = rememberHaptics()
    val downloads by Graph.downloads.items.collectAsState()
    val liked by Graph.library.data.collectAsState()
    val press = remember { MutableInteractionSource() }
    val isLiked = remember(liked.liked, t.id) { liked.liked.any { it.id == t.id } }
    val meta = remember(t.artist, t.album, t.title, t.durationMs) {
        listOfNotNull(t.artist.ifBlank { null }, t.album?.takeIf { it.isNotBlank() && it != t.title }, t.durationMs.takeIf { it > 0 }?.let(::formatTime)).joinToString(" · ")
    }
    SwipeRow(
        enabled = onMore != null,
        onSwipeRight = { Graph.player.playNext(t); Pills.show("Playing next") },
        onSwipeLeft = { Graph.player.addToQueue(listOf(t)); Pills.show("Added to queue") }
    ) {
        Row(
            Modifier.fillMaxWidth().pressScale(press, 0.98f).combinedClickable(
                interactionSource = press,
                indication = ripple(),
                onClick = onClick,
                onLongClickLabel = if (onLongPress != null) "Song options" else null,
                onLongClick = onLongPress?.let { lp -> { rowHaptics.confirm(); lp() } }
            ).padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) Text("$index", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
            if (showArt) { Artwork(t.artUrl, Modifier.size(48.dp), Ms.r8); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(
                    t.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.explicit) { Badge("E"); Spacer(Modifier.width(4.dp)) }
                    if (downloads[t.id]?.state == DownloadEntry.State.DONE) {
                        Icon(Icons.Rounded.DownloadDone, "Downloaded", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (isLiked) {
                        Icon(Icons.Rounded.Favorite, "Liked", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) trailing()
            else if (onMore != null) IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, Ms.r4).padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
fun CollectionCard(c: Collection, width: Dp = 152.dp, onClick: () -> Unit) {
    Column(Modifier.width(width).pressable(onClick = onClick)) {
        Artwork(c.artUrl, Modifier.fillMaxWidth().aspectRatio(1f).sharedCover("cover:${c.id}"), Ms.r16)
        Spacer(Modifier.height(8.dp))
        Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        c.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun TrackCard(t: Track, width: Dp = 152.dp, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    Column(Modifier.width(width).pressable(onLongClick = onLong, onClick = onClick)) {
        Artwork(t.artUrl, Modifier.fillMaxWidth().aspectRatio(1f), Ms.r16)
        Spacer(Modifier.height(8.dp))
        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            subtitle?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun <T> HorizontalShelf(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    val unique = remember(items) { items.distinctBy(key) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(unique, key = { key(it) }, contentType = { "shelf-card" }) { content(it) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: String? = null, modifier: Modifier = Modifier, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    val online by Graph.online.collectAsState()
    EmptyState(
        if (online) Icons.Rounded.ErrorOutline else Icons.Rounded.CloudOff,
        if (online) "Couldn't load this" else "You're offline",
        if (online) message else "Connect to the internet to browse. Your downloads and music on this device still play.",
        "Retry", onAction = onRetry,
    )
    if (secondary != null && onSecondary != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = onSecondary) { Text(secondary) } }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 3.dp) }
}

@Composable
fun OfflineBanner() {
    val online by Graph.online.collectAsState()
    AnimatedVisibility(!online, enter = expandVertically(), exit = shrinkVertically()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Offline — downloads and music on this device still play", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
fun GlassTopBar(title: String, onBack: (() -> Unit)?, height: MutableState<Dp>? = null, actions: @Composable () -> Unit = {}) {
    val density = LocalDensity.current
    val measure = if (height != null) Modifier.onSizeChanged { height.value = with(density) { it.height.toDp() } } else Modifier
    val bg = MaterialTheme.colorScheme.background
    val sbTop = WindowInsets.statusBars.getTop(density)
    val scrim = Modifier.drawBehind {
        val h = size.height.coerceAtLeast(1f)
        val sb = (sbTop / h).coerceIn(0f, 0.9f)
        drawRect(Brush.verticalGradient(0f to bg.copy(alpha = 0.96f), sb to bg.copy(alpha = 0.90f), 1f to bg.copy(alpha = 0f)))
    }
    Row(
        Modifier.fillMaxWidth().then(measure).then(scrim).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Surface(onClick = onBack, shape = CircleShape, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp).glass(CircleShape)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.glass(Ms.pill).padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        actions()
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Composable
fun SoftArt(url: String?, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    Crossfade(url, modifier, animationSpec = tween(if (still) 0 else 700), label = "softArt") { u ->
        if (u != null) {
            val ctx = LocalPlatformContext.current
            val req = remember(u) { ImageRequest.Builder(ctx).data(sizedArtUrl(u, 120)).size(32, 32).build() }
            AsyncImage(
                model = req,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        } else Box(Modifier.fillMaxSize())
    }
}
