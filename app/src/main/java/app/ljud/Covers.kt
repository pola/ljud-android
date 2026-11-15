package app.ljud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads cover images from Spotify's CDN and keeps them in a memory cache. */
object Covers {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    /** Blocking; returns null on failure. */
    fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }?.also { cache.put(url, it) }
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }
}

/** A cover image of [size], with an icon placeholder while loading or if there is none. */
@Composable
fun Cover(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    placeholder: ImageVector = LjudIcons.Note,
) {
    val bitmap by produceState(url?.let(Covers::cached), url) {
        value = url?.let(Covers::cached)
        if (url != null && value == null) value = withContext(Dispatchers.IO) { Covers.load(url) }
    }
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(placeholder, null, Modifier.size(size / 2), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
