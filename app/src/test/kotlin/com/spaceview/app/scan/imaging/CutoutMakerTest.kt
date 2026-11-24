package com.spaceview.app.scan.imaging

import com.spaceview.engine.ArgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLAN v3 W2 + review MAJOR 5: when the Review shows a background-free cutout, and when it keeps the rectangle. A cutout
 * is always the full measured W × H canvas (never cropped, so never stretched).
 */
class CutoutMakerTest {
    private val w = 40
    private val h = 30
    private val photo = ArgbImage(w, h, IntArray(w * h) { 0xFF000000.toInt() or (it * 977 and 0xFFFFFF) })

    /** A subject covering x 2..37, y 1..29 (the outline was measured around it, a little loosely). */
    private fun subjectMask(x0: Int = 2, x1: Int = 38, y0: Int = 1, y1: Int = 30) =
        FloatArray(w * h) { i -> if (i % w in x0 until x1 && i / w in y0 until y1) 0.96f else 0.03f }

    @Test fun flatObjectsAreTheirRectangle() {
        val c = CutoutMaker.choose(photo, flat = true, mask = subjectMask())
        assertEquals(CutoutStatus.FLAT, c.status)
        assertNull(c.cutout)
        assertSame(photo, c.rectangle)
    }

    @Test fun trustedMaskKeepsTheWholeCanvasWithATransparentBackground() {
        val c = CutoutMaker.choose(photo, flat = false, mask = subjectMask())
        assertEquals(CutoutStatus.REMOVED, c.status)
        val cut = c.cutout!!
        // Same size as the rectified photo = the measured W × H: nothing cropped, nothing to stretch.
        assertEquals(w, cut.width)
        assertEquals(h, cut.height)
        assertEquals("subject: the photo's own pixel, opaque", photo.argb[5 * w + 10], cut.argb[5 * w + 10])
        assertEquals("background: transparent", 0, cut.argb[0] ushr 24)
        assertEquals("its colour is kept under the alpha", photo.argb[0] and 0xFFFFFF, cut.argb[0] and 0xFFFFFF)
        assertFalse(cut.isOpaque)
        assertSame(photo, c.rectangle)
    }

    /** MAJOR 5 scenario: a chair whose legs come out faint (0.36–0.45). The scale never changes; the legs stay visible. */
    @Test fun faintThinPartsKeepTheScaleAndStayInThePicture() {
        val mask = FloatArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x in 2 until 38 && y in 0 until 19 -> 0.95f // seat and back: top 65 %
                y >= 19 && (x in 3 until 6 || x in 34 until 37) -> 0.42f // legs, below the 0.5 mark
                else -> 0.02f
            }
        }
        val c = CutoutMaker.choose(photo, flat = false, mask = mask)
        assertEquals(CutoutStatus.REMOVED, c.status)
        val cut = c.cutout!!
        assertEquals(w, cut.width); assertEquals(h, cut.height)
        val legAlpha = cut.argb[25 * w + 4] ushr 24
        assertTrue("faint legs are drawn semi-transparent, not dropped: $legAlpha", legAlpha in 1..254)
        assertEquals(0, cut.argb[25 * w + 20] ushr 24)
    }

    @Test fun aMaskThatLeavesMuchOfTheOutlineEmptyKeepsTheRectangle() {
        // Legs lost completely (confidence 0.1): the visible part spans 63 % of the height.
        val legless = FloatArray(w * h) { i -> if (i % w in 2 until 38 && i / w < 19) 0.95f else 0.1f }
        assertEquals(CutoutStatus.NOT_SURE, CutoutMaker.choose(photo, false, legless).status)
        // A small blob in a corner is not the measured object.
        val blob = CutoutMaker.choose(photo, false, subjectMask(0, 12, 0, 12))
        assertEquals(CutoutStatus.NOT_SURE, blob.status)
        assertNull(blob.cutout)
        assertSame(photo, blob.rectangle)
    }

    @Test fun missingOrUntrustedMaskKeepsTheRectangle() {
        assertEquals(CutoutStatus.NOT_AVAILABLE, CutoutMaker.choose(photo, false, null).status)
        assertEquals("wrong size", CutoutStatus.NOT_AVAILABLE, CutoutMaker.choose(photo, false, FloatArray(5)).status)
        assertEquals("nothing found", CutoutStatus.NOT_SURE, CutoutMaker.choose(photo, false, FloatArray(w * h) { 0.01f }).status)
        assertEquals("confused", CutoutStatus.NOT_SURE, CutoutMaker.choose(photo, false, FloatArray(w * h) { 0.55f }).status)
    }
}
