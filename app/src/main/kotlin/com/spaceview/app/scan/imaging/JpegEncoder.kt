package com.spaceview.app.scan.imaging

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** JPEG helpers for the scan pipeline. Call off the main thread. */
object JpegEncoder {
    /** Quality for the rectified front photo handed to the gallery (it re-encodes at q82 within its budget). */
    const val SCAN_QUALITY = 90

    /** Gallery / share quality: ~150–250 KB for a 1024 px photo. */
    const val GALLERY_QUALITY = 82

    /** JPEG bytes of [bitmap] at [quality] (0–100). Transparent pixels become black (JPEG has no alpha). */
    fun encode(bitmap: Bitmap, quality: Int = SCAN_QUALITY): ByteArray {
        val out = ByteArrayOutputStream(estimateBytes(bitmap.width, bitmap.height))
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), out)) { "JPEG encode failed" }
        return out.toByteArray()
    }

    /**
     * Encodes at [quality], stepping the quality down by 8 (not below [minQuality]) until the result is at most
     * [maxBytes]. Returns the smallest attempt if none fits.
     */
    fun encodeWithin(bitmap: Bitmap, maxBytes: Int, quality: Int = GALLERY_QUALITY, minQuality: Int = 50): ByteArray {
        var q = quality.coerceIn(0, 100)
        var best = encode(bitmap, q)
        while (best.size > maxBytes && q - 8 >= minQuality) {
            q -= 8
            best = encode(bitmap, q)
        }
        return best
    }

    /** True if [bytes] starts with the JPEG SOI marker (FF D8). */
    fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    /** Initial buffer guess: ~0.25 byte per pixel, capped. */
    internal fun estimateBytes(width: Int, height: Int): Int = (width.toLong() * height / 4).coerceIn(16_384, 1_048_576).toInt()
}
