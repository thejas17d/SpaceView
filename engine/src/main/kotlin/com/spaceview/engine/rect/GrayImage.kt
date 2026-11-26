package com.spaceview.engine.rect

import kotlin.math.floor

/**
 * A grey (luma) image for the rectangle detector (W9). Pixel (x, y) has its **centre** at integer coordinates (x, y);
 * [sample] interpolates bilinearly between centres. Values are grey levels (0–255 for camera luma).
 */
class GrayImage(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "pixels must be width × height" }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]

    /** Bilinear value at ([x], [y]), clamped to the image. */
    fun sample(x: Double, y: Double): Double {
        val cx = x.coerceIn(0.0, (width - 1).toDouble())
        val cy = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = floor(cx).toInt().coerceAtMost(width - 2).coerceAtLeast(0)
        val y0 = floor(cy).toInt().coerceAtMost(height - 2).coerceAtLeast(0)
        val fx = cx - x0
        val fy = cy - y0
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val a = pixels[y0 * width + x0]
        val b = pixels[y0 * width + x1]
        val c = pixels[y1 * width + x0]
        val d = pixels[y1 * width + x1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /**
     * Half-size image (2 × 2 box average). A half-size pixel (i, j) has its centre at full-size (2i + 0.5, 2j + 0.5):
     * map back with `full = 2 · half + 0.5`.
     */
    fun downsample2(): GrayImage {
        val w = width / 2
        val h = height / 2
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val r0 = 2 * y * width
            val r1 = r0 + width
            for (x in 0 until w) {
                val i = 2 * x
                out[y * w + x] = (pixels[r0 + i] + pixels[r0 + i + 1] + pixels[r1 + i] + pixels[r1 + i + 1]) * 0.25f
            }
        }
        return GrayImage(w, h, out)
    }

    companion object {
        /** The Y plane of an NV21 / YUV image ([rowStride] bytes per row) as grey levels. */
        fun fromLuma(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): GrayImage {
            require(rowStride >= width && luma.size >= rowStride * (height - 1) + width)
            val p = FloatArray(width * height)
            for (y in 0 until height) {
                val r = y * rowStride
                for (x in 0 until width) p[y * width + x] = (luma[r + x].toInt() and 0xFF).toFloat()
            }
            return GrayImage(width, height, p)
        }
    }
}
