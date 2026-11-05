package com.spaceview.ar

/**
 * An NV21 copy of the CPU camera image (Y plane, then interleaved V/U at half resolution), in sensor
 * orientation. Copied out on the frame thread so the ARCore image can be closed at once.
 */
class Nv21Image(val bytes: ByteArray, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && bytes.size >= width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2))
    }
}

/** NV21 → ARGB conversion (pure Kotlin, no RenderScript and no JPEG round trip). Call off the main thread. */
object Nv21 {

    /**
     * NV21 bytes from YUV_420_888 planes, honouring each plane's row and pixel stride (padded rows,
     * interleaved or planar chroma). Chroma covers ⌈w/2⌉ × ⌈h/2⌉ samples, clamped to what the planes hold.
     */
    fun fromPlanes(
        width: Int, height: Int,
        y: java.nio.ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: java.nio.ByteBuffer, uRowStride: Int, uPixelStride: Int,
        v: java.nio.ByteBuffer, vRowStride: Int, vPixelStride: Int,
    ): ByteArray {
        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        val out = ByteArray(width * height + 2 * chromaW * chromaH)
        var o = 0
        val yBulk = y.duplicate() // bulk row copies without moving the caller's buffer position
        for (r in 0 until height) {
            val rowStart = r * yRowStride
            if (yPixelStride == 1) {
                yBulk.position(rowStart)
                yBulk.get(out, o, width)
                o += width
            } else {
                for (c in 0 until width) out[o++] = y.get(rowStart + c * yPixelStride)
            }
        }
        for (r in 0 until chromaH) for (c in 0 until chromaW) {
            val vi = r * vRowStride + c * vPixelStride
            val ui = r * uRowStride + c * uPixelStride
            out[o++] = if (vi < v.limit()) v.get(vi) else 128.toByte()
            out[o++] = if (ui < u.limit()) u.get(ui) else 128.toByte()
        }
        return out
    }

    /**
     * Full-range (JFIF / BT.601) YUV → opaque ARGB_8888 pixels, row-major, [Nv21Image.width] wide.
     * The camera's YUV_420_888 output is full range on Android.
     */
    fun toArgb(img: Nv21Image, out: IntArray = IntArray(img.width * img.height)): IntArray {
        val w = img.width
        val h = img.height
        require(out.size >= w * h)
        val src = img.bytes
        val uvStart = w * h
        // Rounded up, as [fromPlanes] writes them; an image of exactly w·h·3/2 bytes (even sizes) agrees.
        val chromaW = (w + 1) / 2
        val chromaH = (h + 1) / 2
        for (y in 0 until h) {
            val cy = (y / 2).coerceAtMost(chromaH - 1).coerceAtLeast(0)
            val uvRow = uvStart + cy * chromaW * 2
            val yRow = y * w
            for (x in 0 until w) {
                val yy = src[yRow + x].toInt() and 0xFF
                val cx = (x / 2).coerceAtMost(chromaW - 1).coerceAtLeast(0)
                val i = uvRow + cx * 2
                val v = if (chromaW > 0 && chromaH > 0) (src[i].toInt() and 0xFF) - 128 else 0
                val u = if (chromaW > 0 && chromaH > 0) (src[i + 1].toInt() and 0xFF) - 128 else 0
                // Fixed point (×1024): R = Y + 1.402 V, G = Y − 0.344 U − 0.714 V, B = Y + 1.772 U.
                val r = clamp(yy + ((1436 * v) shr 10))
                val g = clamp(yy - ((352 * u + 731 * v) shr 10))
                val b = clamp(yy + ((1815 * u) shr 10))
                out[yRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun clamp(c: Int): Int = if (c < 0) 0 else if (c > 255) 255 else c
}
