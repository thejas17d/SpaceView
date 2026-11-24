package com.spaceview.app.scan.imaging

import com.spaceview.engine.ArgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** PLAN v3 W2: when the Review shows a background-free cutout, and when it keeps the rectangle. */
class CutoutMakerTest {
    private val w = 40
    private val h = 30
    private val photo = ArgbImage(w, h, IntArray(w * h) { 0xFF000000.toInt() or (it * 977 and 0xFFFFFF) })

    /** A chair-like subject covering x 6..33, y 2..29 (the outline was measured around it). */
    private fun subjectMask(x0: Int = 6, x1: Int = 34, y0: Int = 2, y1: Int = 30) =
        FloatArray(w * h) { i -> if (i % w in x0 until x1 && i / w in y0 until y1) 0.96f else 0.03f }

    @Test fun flatObjectsAreTheirRectangle() {
        val c = CutoutMaker.choose(photo, flat = true, mask = subjectMask())
        assertEquals(CutoutStatus.FLAT, c.status)
        assertNull(c.cutout)
        assertSame(photo, c.rectangle)
    }

    @Test fun trustedMaskCropsTheSubjectToItsBounds() {
        val c = CutoutMaker.choose(photo, flat = false, mask = subjectMask())
        assertEquals(CutoutStatus.REMOVED, c.status)
        val cut = c.cutout!!
        assertEquals(28, cut.width)
        assertEquals(28, cut.height)
        // Inside the subject: the photo's own colour, opaque.
        assertEquals(photo.argb[2 * w + 6], cut.argb[0])
        assertSame(photo, c.rectangle)
    }

    @Test fun backgroundPixelsAreTransparentInsideTheBounds() {
        // An L-shaped subject: its bounding box contains background.
        val mask = FloatArray(w * h) { i ->
            val x = i % w; val y = i / w
            if ((x in 4 until 36 && y in 20 until 30) || (x in 4 until 12 && y in 0 until 30)) 0.97f else 0.02f
        }
        val cut = CutoutMaker.choose(photo, flat = false, mask = mask).cutout!!
        assertEquals(32, cut.width); assertEquals(30, cut.height)
        assertEquals("background corner", 0, cut.argb[cut.width - 1] ushr 24)
        assertEquals("subject", 255, cut.argb[0] ushr 24)
        assertFalse(cut.isOpaque)
    }

    @Test fun missingOrUntrustedMaskKeepsTheRectangle() {
        assertEquals(CutoutStatus.NOT_AVAILABLE, CutoutMaker.choose(photo, false, null).status)
        assertEquals("wrong size", CutoutStatus.NOT_AVAILABLE, CutoutMaker.choose(photo, false, FloatArray(5)).status)
        assertEquals("nothing found", CutoutStatus.NOT_SURE, CutoutMaker.choose(photo, false, FloatArray(w * h) { 0.01f }).status)
        assertEquals("confused", CutoutStatus.NOT_SURE, CutoutMaker.choose(photo, false, FloatArray(w * h) { 0.55f }).status)
        // A small blob in a corner is not the measured object.
        val blob = CutoutMaker.choose(photo, false, subjectMask(0, 12, 0, 12))
        assertEquals(CutoutStatus.NOT_SURE, blob.status)
        assertNull(blob.cutout)
        assertSame(photo, blob.rectangle)
    }
}
