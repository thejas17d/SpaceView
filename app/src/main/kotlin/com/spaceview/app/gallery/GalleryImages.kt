package com.spaceview.app.gallery

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.spaceview.app.R
import com.spaceview.app.ui.theme.SpaceViewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Small decoded-image cache for gallery thumbnails and photos (keyed by path + item version). */
private object GalleryBitmapCache {
    private val cache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }
}

/** Decodes [file] with its long side at most about [maxPx] (power-of-two subsampling). Null if unreadable. */
private fun decodeFile(file: File, maxPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/**
 * A gallery image loaded off the main thread; shows a hairline placeholder ("No photo" when [file] is null or
 * unreadable). [version] (the item's updatedAt) invalidates the cache after an edit.
 */
@Composable
fun GalleryImage(
    file: File?,
    version: Long,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxPx: Int = 1024,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val key = file?.let { "${it.path}#$version#$maxPx" }
    val bitmap by produceState(initialValue = key?.let { GalleryBitmapCache.get(it) }, key) {
        value = key?.let { k ->
            GalleryBitmapCache.get(k)
                ?: withContext(Dispatchers.IO) { file?.let { decodeFile(it, maxPx) } }?.also { GalleryBitmapCache.put(k, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = contentDescription, modifier = Modifier.matchParentSize(), contentScale = contentScale)
        } else if (file == null) {
            Text(
                stringResource(R.string.gallery_no_photo),
                style = SpaceViewTheme.typography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Android share sheet for a gallery item (WhatsApp and every other target need nothing special). */
object GalleryShare {
    /** `<applicationId>.fileprovider`, declared in the manifest. */
    fun authority(context: Context): String = context.packageName + ".fileprovider"

    /**
     * Opens the chooser with [photo] (a share copy from `GalleryRepository.shareCopy`, so the shared link never
     * changes under the receiver if the item is edited) and [text]; text only when [photo] is null.
     * Returns false if nothing could be shared. Never throws.
     */
    fun share(context: Context, photo: File?, text: String, chooserTitle: String): Boolean = try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            if (photo != null) {
                val uri = FileProvider.getUriForFile(context, authority(context), photo)
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(photo.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        val chooser = Intent.createChooser(intent, chooserTitle)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: IllegalArgumentException) {
        false // getUriForFile: the file is outside the provider's paths
    } catch (e: SecurityException) {
        false
    }

    /** A safe file name for the shared photo: "SpaceView-Cafe-frame.jpg". */
    fun fileName(title: String): String {
        val slug = title.map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(40)
        return if (slug.isEmpty()) "SpaceView.jpg" else "SpaceView-$slug.jpg"
    }
}
