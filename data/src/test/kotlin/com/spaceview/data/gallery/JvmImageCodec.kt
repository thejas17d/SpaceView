package com.spaceview.data.gallery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Desktop-JVM [GalleryImageCodec] for unit tests, so the size budget is measured with a real JPEG encoder.
 *
 * Android unit tests compile against android.jar, which has no `javax.imageio`; the JDK running the
 * tests does, so it is reached through reflection. The JDK has no WebP encoder: thumbnails are encoded
 * as JPEG at the same quality, a conservative stand-in (lossy WebP is typically 25–35% smaller).
 */
internal object JvmImageCodec : GalleryImageCodec {
    override fun decode(encoded: ByteArray, maxLongSide: Int): GalleryImage? {
        // Like BitmapFactory: undecodable input gives null, not an exception.
        val (w, h, px) = runCatching { JvmImageIo.decode(encoded) }.getOrNull() ?: return null
        return JvmImage(w, h, px).scaled(maxLongSide)
    }
}

internal class JvmImage(override val width: Int, override val height: Int, val argb: IntArray) : GalleryImage {
    override fun scaled(maxLongSide: Int): GalleryImage {
        val long = max(width, height)
        if (long <= maxLongSide) return this
        val s = maxLongSide.toDouble() / long
        val w = max(1, (width * s).roundToInt())
        val h = max(1, (height * s).roundToInt())
        return JvmImage(w, h, areaResample(argb, width, height, w, h))
    }

    override fun jpeg(quality: Int): ByteArray = JvmImageIo.encodeJpeg(width, height, argb, quality / 100f)

    override fun webp(quality: Int): ByteArray = JvmImageIo.encodeJpeg(width, height, argb, quality / 100f)

    override fun release() = Unit

    private companion object {
        /** Box-filter downscale (each output pixel averages the source pixels it covers). */
        fun areaResample(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
            val out = IntArray(dw * dh)
            for (y in 0 until dh) {
                val y0 = y * sh / dh
                val y1 = max(y0 + 1, (y + 1) * sh / dh)
                for (x in 0 until dw) {
                    val x0 = x * sw / dw
                    val x1 = max(x0 + 1, (x + 1) * sw / dw)
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (yy in y0 until y1) for (xx in x0 until x1) {
                        val p = src[yy * sw + xx]
                        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                    }
                    out[y * dw + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
            return out
        }
    }
}

/** javax.imageio through reflection (see [JvmImageCodec]). */
internal object JvmImageIo {
    private val int = Integer.TYPE
    private val bufferedImage = Class.forName("java.awt.image.BufferedImage")
    private val imageIo = Class.forName("javax.imageio.ImageIO")
    private val imageWriter = Class.forName("javax.imageio.ImageWriter")
    private val writeParam = Class.forName("javax.imageio.ImageWriteParam")
    private val iioImage = Class.forName("javax.imageio.IIOImage")
    private val renderedImage = Class.forName("java.awt.image.RenderedImage")
    private val iioMetadata = Class.forName("javax.imageio.metadata.IIOMetadata")

    init {
        System.setProperty("java.awt.headless", "true")
    }

    /** Baseline JPEG of opaque ARGB pixels at [quality] (0..1, IJG scaling like Android's encoder). */
    fun encodeJpeg(w: Int, h: Int, argb: IntArray, quality: Float): ByteArray {
        val img = bufferedImage.getConstructor(int, int, int).newInstance(w, h, 1 /* TYPE_INT_RGB */)
        bufferedImage.getMethod("setRGB", int, int, int, int, IntArray::class.java, int, int).invoke(img, 0, 0, w, h, argb, 0, w)
        val writer = (imageIo.getMethod("getImageWritersByFormatName", String::class.java).invoke(null, "jpeg") as Iterator<*>).next()
        val param = imageWriter.getMethod("getDefaultWriteParam").invoke(writer)
        writeParam.getMethod("setCompressionMode", int).invoke(param, 2 /* MODE_EXPLICIT */)
        writeParam.getMethod("setCompressionQuality", java.lang.Float.TYPE).invoke(param, quality)
        val bytes = ByteArrayOutputStream()
        val out = imageIo.getMethod("createImageOutputStream", Any::class.java).invoke(null, bytes)
        try {
            imageWriter.getMethod("setOutput", Any::class.java).invoke(writer, out)
            val iio = iioImage.getConstructor(renderedImage, List::class.java, iioMetadata).newInstance(img, null, null)
            imageWriter.getMethod("write", iioMetadata, iioImage, writeParam).invoke(writer, null, iio, param)
        } finally {
            (out as Closeable).close()
            imageWriter.getMethod("dispose").invoke(writer)
        }
        return bytes.toByteArray()
    }

    /** (width, height, ARGB pixels), or null if not an image. */
    fun decode(bytes: ByteArray): Triple<Int, Int, IntArray>? {
        val img = imageIo.getMethod("read", java.io.InputStream::class.java).invoke(null, ByteArrayInputStream(bytes)) ?: return null
        val w = bufferedImage.getMethod("getWidth").invoke(img) as Int
        val h = bufferedImage.getMethod("getHeight").invoke(img) as Int
        val px = IntArray(w * h)
        bufferedImage.getMethod("getRGB", int, int, int, int, IntArray::class.java, int, int).invoke(img, 0, 0, w, h, px, 0, w)
        return Triple(w, h, px)
    }
}

/** Synthetic test photos (deterministic). */
internal object TestPhotos {
    /**
     * A "typical" rectified front photo of a framed picture: wood-grain frame border, a painted scene
     * (sky gradient, hills, a sun, some foliage texture) and camera sensor noise (σ ≈ 3 levels).
     */
    fun typicalFrame(w: Int = 1024, h: Int = 768, seed: Long = 7): IntArray {
        val rnd = java.util.Random(seed)
        val px = IntArray(w * h)
        val border = (0.07 * minOf(w, h)).toInt()
        for (y in 0 until h) for (x in 0 until w) {
            var r: Double; var g: Double; var b: Double
            val inFrame = x < border || y < border || x >= w - border || y >= h - border
            if (inFrame) {
                val grain = 18 * kotlin.math.sin((x + y) * 0.05 + kotlin.math.sin(y * 0.013) * 6) + 8 * kotlin.math.sin(x * 0.31)
                r = 96 + grain; g = 62 + grain * 0.7; b = 38 + grain * 0.4
            } else {
                val fy = y.toDouble() / h
                val fx = x.toDouble() / w
                val hill = 0.55 + 0.08 * kotlin.math.sin(fx * 9) + 0.04 * kotlin.math.sin(fx * 23)
                if (fy < hill) {
                    r = 120 + 90 * fy; g = 160 + 60 * fy; b = 230 - 30 * fy
                    val dx = fx - 0.72; val dy = fy - 0.22
                    if (dx * dx + dy * dy < 0.004) { r = 250.0; g = 220.0; b = 120.0 }
                } else {
                    val leaf = 25 * kotlin.math.sin(x * 0.21 + kotlin.math.sin(y * 0.17) * 3) * kotlin.math.cos(y * 0.19)
                    r = 60 + leaf; g = 120 + leaf * 1.2; b = 50 + leaf * 0.5
                }
            }
            val n = rnd.nextGaussian() * 3
            px[y * w + x] = rgb(r + n, g + n, b + n)
        }
        return px
    }

    /**
     * A detailed subject (poster with text-like strokes, many hard edges, mid-grey photo noise σ ≈ 5):
     * the busy end of what a real scan looks like.
     */
    fun detailedPoster(w: Int = 1024, h: Int = 768, seed: Long = 3): IntArray {
        val rnd = java.util.Random(seed)
        val px = IntArray(w * h) { 0 }
        val base = DoubleArray(w * h * 3)
        for (i in 0 until w * h) {
            val x = i % w; val y = i / w
            base[3 * i] = 235.0 - 40.0 * y / h; base[3 * i + 1] = 228.0 - 20.0 * x / w; base[3 * i + 2] = 210.0
        }
        // Coloured blocks (pictures on the poster).
        repeat(60) {
            val bw = 20 + rnd.nextInt(180); val bh = 20 + rnd.nextInt(140)
            val bx = rnd.nextInt(w - bw); val by = rnd.nextInt(h - bh)
            val cr = rnd.nextInt(256).toDouble(); val cg = rnd.nextInt(256).toDouble(); val cb = rnd.nextInt(256).toDouble()
            for (y in by until by + bh) for (x in bx until bx + bw) {
                val i = y * w + x
                val shade = 0.8 + 0.2 * kotlin.math.sin(x * 0.07) * kotlin.math.cos(y * 0.05)
                base[3 * i] = cr * shade; base[3 * i + 1] = cg * shade; base[3 * i + 2] = cb * shade
            }
        }
        // Lines of "text": short dark strokes.
        var ty = 12
        while (ty < h - 12) {
            var tx = 10
            while (tx < w - 20) {
                val len = 4 + rnd.nextInt(14)
                if (rnd.nextInt(5) != 0) {
                    for (y in ty until ty + 9) for (x in tx until minOf(w, tx + len)) {
                        if (rnd.nextInt(3) != 0) { val i = y * w + x; base[3 * i] = 25.0; base[3 * i + 1] = 25.0; base[3 * i + 2] = 30.0 }
                    }
                }
                tx += len + 4
            }
            ty += 16
        }
        for (i in 0 until w * h) {
            val n = rnd.nextGaussian() * 5
            px[i] = rgb(base[3 * i] + n, base[3 * i + 1] + n, base[3 * i + 2] + n)
        }
        return px
    }

    /** Worst case: dense high-frequency texture plus heavy noise (σ ≈ 10). Forces the budget step-down. */
    fun noisy(w: Int = 1024, h: Int = 1024, seed: Long = 11): IntArray {
        val rnd = java.util.Random(seed)
        return IntArray(w * h) {
            rgb(128 + rnd.nextGaussian() * 60, 128 + rnd.nextGaussian() * 60, 128 + rnd.nextGaussian() * 60)
        }
    }

    private fun rgb(r: Double, g: Double, b: Double): Int {
        fun c(v: Double) = v.roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}
