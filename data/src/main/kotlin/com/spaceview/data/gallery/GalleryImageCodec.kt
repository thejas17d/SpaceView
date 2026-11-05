package com.spaceview.data.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The image operations the gallery needs (decode, downscale, JPEG / WebP encode). Android uses
 * [AndroidGalleryImageCodec]; the JVM unit tests plug in a desktop codec, so the size budget is
 * checked with a real JPEG encoder.
 */
interface GalleryImageCodec {
    /** Decodes [encoded] (JPEG) with its long side at most [maxLongSide] px (never upscaled); null if undecodable. */
    fun decode(encoded: ByteArray, maxLongSide: Int): GalleryImage?
}

/** A decoded image. Call [release] when done (Android recycles the bitmap). */
interface GalleryImage {
    val width: Int
    val height: Int

    /** A copy with its long side at most [maxLongSide] (this image itself when already small enough). */
    fun scaled(maxLongSide: Int): GalleryImage

    /** JPEG bytes at [quality] (0–100). */
    fun jpeg(quality: Int): ByteArray

    /** Lossy WebP bytes at [quality] (0–100). */
    fun webp(quality: Int): ByteArray

    fun release()
}

/** [GalleryImageCodec] over `android.graphics.Bitmap`. Call off the main thread. */
object AndroidGalleryImageCodec : GalleryImageCodec {
    override fun decode(encoded: ByteArray, maxLongSide: Int): GalleryImage? {
        require(maxLongSide > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Power-of-two subsampling while the long side stays >= the target, then an exact filtered scale.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, opts) ?: return null
        val img = BitmapImage(bmp)
        val out = img.scaled(maxLongSide)
        if (out !== img) img.release()
        return out
    }

    private class BitmapImage(val bitmap: Bitmap) : GalleryImage {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height

        /**
         * Filtered downscale. A single bilinear pass only averages 2×2 source pixels, so a 4:1 reduction (1024 →
         * 256 thumbnail) would alias fine text and stripes; halve first while the image is at least twice the
         * target, then make one exact filtered step (each pass stays ≤ 2:1).
         */
        override fun scaled(maxLongSide: Int): GalleryImage {
            val long = max(width, height)
            if (long <= maxLongSide) return this
            var current = bitmap
            while (max(current.width, current.height) / 2 >= maxLongSide) {
                val half = Bitmap.createScaledBitmap(current, max(1, current.width / 2), max(1, current.height / 2), true)
                if (current !== bitmap && half !== current) current.recycle()
                current = half
            }
            val s = maxLongSide.toDouble() / max(current.width, current.height)
            val w = max(1, (current.width * s).roundToInt())
            val h = max(1, (current.height * s).roundToInt())
            val out = if (w == current.width && h == current.height) current else Bitmap.createScaledBitmap(current, w, h, true)
            if (current !== bitmap && out !== current) current.recycle()
            return BitmapImage(out) // never `bitmap` itself: the long side shrank
        }

        override fun jpeg(quality: Int): ByteArray = encode(Bitmap.CompressFormat.JPEG, quality)

        override fun webp(quality: Int): ByteArray {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            return encode(format, quality)
        }

        private fun encode(format: Bitmap.CompressFormat, quality: Int): ByteArray {
            val out = ByteArrayOutputStream(64 * 1024)
            check(bitmap.compress(format, quality.coerceIn(0, 100), out)) { "Bitmap.compress($format) failed" }
            return out.toByteArray()
        }

        override fun release() {
            bitmap.recycle()
        }
    }
}
