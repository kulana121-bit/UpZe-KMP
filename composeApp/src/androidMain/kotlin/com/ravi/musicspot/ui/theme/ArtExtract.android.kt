package com.ravi.musicspot.ui.theme

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import com.ravi.musicspot.core.PlatformActions
import com.ravi.musicspot.logic.ArtTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val pickCache = android.util.LruCache<String, ArtTheme.Picked>(64)

actual suspend fun extractArtTheme(url: String?): ArtTheme.Picked? {
    if (url.isNullOrEmpty()) return null
    pickCache.get(url)?.let { return it }
    val context = PlatformActions.getAppContext() ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val req = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).bitmapConfig(Bitmap.Config.RGB_565).build()
            val res = SingletonImageLoader.get(context).execute(req) as? SuccessResult ?: return@runCatching null
            val bmp: Bitmap = (res.image as? BitmapImage)?.bitmap ?: return@runCatching null
            val p = Palette.from(bmp).maximumColorCount(16).generate()
            ArtTheme.pick(p.swatches.map { it.rgb to it.population })?.also { pickCache.put(url, it) }
        }.getOrNull()
    }
}
