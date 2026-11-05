package com.spaceview.app.scan

import com.spaceview.app.gallery.GalleryFormatter.EditUnit
import com.spaceview.ar.FaceChoice
import com.spaceview.ar.FrontFace
import com.spaceview.ar.ImprovementTip
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.DimensionSource
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class ScanDraftBuilderTest {
    private val face = FrontFace(listOf(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3.ZERO), Vec3.UNIT_Z)
    private val choice = FaceChoice(0, Vec3.UP, Vec3.UNIT_Z)

    private fun m(value: Double, bound95: Double) = Measurement(value, bound95 / Uncertainty.K95)

    private fun flat(quality: Quality = Quality.FAIR, consistent: Boolean = true) = ScanOutline(
        ScanShape.FLAT, width = m(0.6, 0.012), height = m(0.4, 0.011), depth = null,
        diagonal = m(sqrt(0.52), 0.013), quality = quality, calibrated = false, geometryConsistent = consistent, face = face, choice = choice,
    )

    private fun box() = ScanOutline(
        ScanShape.BOX, width = m(0.6, 0.012), height = m(0.85, 0.015), depth = m(0.55, 0.012),
        diagonal = null, quality = Quality.FAIR, calibrated = true, geometryConsistent = true, face = face, choice = choice,
    )

    @Test
    fun kindsMapToShapesAndPresetDepths() {
        assertEquals(ScanShape.FLAT, ScanDraftBuilder.shapeFor(ObjectKind.FRAME))
        assertEquals(ScanShape.FLAT, ScanDraftBuilder.shapeFor(ObjectKind.TV))
        assertEquals(ScanShape.BOX, ScanDraftBuilder.shapeFor(ObjectKind.APPLIANCE))
        assertEquals(ScanShape.BOX, ScanDraftBuilder.shapeFor(ObjectKind.BOX))
        assertEquals(ScanShape.BOX, ScanDraftBuilder.shapeFor(ObjectKind.OTHER))
        assertEquals(0.03, ScanDraftBuilder.presetDepth(ObjectKind.FRAME)!!, 1e-12)
        assertEquals(0.08, ScanDraftBuilder.presetDepth(ObjectKind.TV)!!, 1e-12)
        assertNull(ScanDraftBuilder.presetDepth(ObjectKind.APPLIANCE))
    }

    /** The stored ± is never below the engine's and never zero for a measured value. */
    @Test
    fun measuredBoundRoundsUp() {
        val rng = Random(3)
        repeat(2000) {
            val meas = Measurement(0.01 + rng.nextDouble() * 3, rng.nextDouble() * 0.02)
            val d = ScanDraftBuilder.measured(meas)
            assertFalse(d.isEstimate)
            assertTrue("${d.bound95} < ${meas.bound95}", d.bound95 >= meas.bound95)
            assertTrue(d.bound95 > 0)
            assertTrue(d.bound95 - meas.bound95 <= 1e-4 + 1e-12)
            assertEquals(meas.value, d.meters, 0.5e-4 + 1e-12)
        }
        // Exactly on a step stays there (no extra step from binary noise).
        assertEquals(0.0123, ScanDraftBuilder.measured(Measurement(0.5, 0.0123 / Uncertainty.K95)).bound95, 1e-12)
    }

    @Test
    fun flatObjectHasMeasuredFaceAndPresetDepth() {
        val v = ScanDraftBuilder.values(ObjectKind.FRAME, flat())
        assertFalse(v.width.isEstimate)
        assertFalse(v.height.isEstimate)
        assertTrue(v.width.bound95 >= 0.012)
        assertTrue(v.depth.isEstimate)
        assertEquals(DimensionSource.PRESET, v.depth.source)
        assertEquals(0.0, v.depth.bound95, 0.0)
        assertEquals(ScanDraftBuilder.FRAME_DEPTH_M, v.depth.meters, 1e-12)
        assertEquals(Quality.FAIR, v.quality) // as seen in AR
        // Measured diagonal, with the Gallery's rule (m11): ±95 = (w·b_w + h·b_h) / d.
        assertFalse(v.diagonal!!.isEstimate)
        val d = sqrt(v.width.meters * v.width.meters + v.height.meters * v.height.meters)
        assertEquals(d, v.diagonal!!.meters, 1e-12)
        assertEquals((v.width.meters * v.width.bound95 + v.height.meters * v.height.bound95) / d, v.diagonal!!.bound95, 1e-12)
        assertEquals(ScanDraftBuilder.TV_DEPTH_M, ScanDraftBuilder.values(ObjectKind.TV, flat()).depth.meters, 1e-12)
        assertTrue(v.isSavable)
    }

    @Test
    fun boxHasThreeMeasuredSizesAndNoDiagonal() {
        val v = ScanDraftBuilder.values(ObjectKind.APPLIANCE, box())
        assertFalse(v.depth.isEstimate)
        assertEquals(0.55, v.depth.meters, 1e-9)
        assertNull(v.diagonal)
        assertTrue(v.calibrated)
    }

    @Test
    fun anEditedValueBecomesAnEstimate() {
        val v = ScanDraftBuilder.values(ObjectKind.TV, flat(), mapOf(ScanDim.WIDTH to 0.9525))
        assertTrue(v.width.isEstimate)
        assertEquals(DimensionSource.TYPED, v.width.source)
        assertEquals(0.0, v.width.bound95, 0.0)
        assertEquals(0.9525, v.width.meters, 1e-12)
        // The diagonal now rests on a typed value: estimate, no ±.
        assertEquals(DimensionSource.TYPED, v.diagonal!!.source)
        assertEquals(sqrt(0.9525 * 0.9525 + 0.4 * 0.4), v.diagonal!!.meters, 1e-4)
        // The badge rates only what is still measured (H: ±1.1 cm on 40 cm → FAIR).
        assertEquals(Quality.of(Measurement(v.height.meters, v.height.bound95 / Uncertainty.K95)), v.quality)
    }

    @Test
    fun editingThePresetDepthKeepsTheMeasuredBadge() {
        val v = ScanDraftBuilder.values(ObjectKind.FRAME, flat(Quality.GOOD), mapOf(ScanDim.DEPTH to 0.05))
        assertEquals(Quality.GOOD, v.quality)
        assertTrue(v.depth.isEstimate)
        assertEquals(0.05, v.depth.meters, 1e-12)
    }

    @Test
    fun nothingMeasuredOrBadGeometryIsRough() {
        val all = mapOf(ScanDim.WIDTH to 0.5, ScanDim.HEIGHT to 0.4, ScanDim.DEPTH to 0.03)
        val v = ScanDraftBuilder.values(ObjectKind.FRAME, flat(Quality.GOOD), all)
        assertEquals(Quality.ROUGH, v.quality)
        assertFalse(v.calibrated)
        val skewed = ScanDraftBuilder.values(ObjectKind.FRAME, flat(Quality.ROUGH, consistent = false), mapOf(ScanDim.WIDTH to 0.5))
        assertEquals(Quality.ROUGH, skewed.quality)
    }

    @Test
    fun typedSizesConvertUnits() {
        assertEquals(0.6096, ScanDraftBuilder.parseEdit("24", EditUnit.IN)!!, 1e-9)
        assertEquals(0.6, ScanDraftBuilder.parseEdit("23 5/8", EditUnit.IN)!!, 1e-3)
        assertEquals(0.615, ScanDraftBuilder.parseEdit("61,5", EditUnit.CM)!!, 1e-9)
        assertEquals(0.03, ScanDraftBuilder.parseEdit("3 cm", EditUnit.CM)!!, 1e-9)
        assertNull(ScanDraftBuilder.parseEdit("abc", EditUnit.CM))
        assertNull(ScanDraftBuilder.parseEdit("6000", EditUnit.CM)) // 60 m: outside the gallery's range
        assertNull(ScanDraftBuilder.parseEdit("0", EditUnit.CM))
    }

    @Test
    fun unsavableSizesBlockSaving() {
        assertFalse(ScanDraftBuilder.values(ObjectKind.FRAME, flat(), mapOf(ScanDim.DEPTH to 0.0001)).isSavable)
    }

    @Test
    fun reviewTip() {
        assertNull(ScanDraftBuilder.reviewTip(Quality.GOOD, ImprovementTip.MOVE_CLOSER))
        assertEquals(ImprovementTip.MOVE_CLOSER, ScanDraftBuilder.reviewTip(Quality.FAIR, ImprovementTip.MOVE_CLOSER))
        // Calibration does not carry into a scan session: never suggested here.
        assertEquals(ImprovementTip.MEASURE_AGAIN, ScanDraftBuilder.reviewTip(Quality.ROUGH, ImprovementTip.CALIBRATE))
        assertEquals(ImprovementTip.MEASURE_AGAIN, ScanDraftBuilder.reviewTip(Quality.ROUGH, null))
    }

    @Test
    fun labelFallsBackToTheSuggestion() {
        assertEquals("TV", ScanDraftBuilder.label("   ", "TV"))
        assertEquals("TV", ScanDraftBuilder.label(null, "TV"))
        assertEquals("Café frame", ScanDraftBuilder.label("  Café frame ", "Frame"))
        assertEquals(80, ScanDraftBuilder.label("x".repeat(200), "Frame").length)
    }

    @Test
    fun draftCarriesExactlyTheReviewedValues() {
        val v = ScanDraftBuilder.values(ObjectKind.BOX, box(), mapOf(ScanDim.HEIGHT to 0.9))
        val jpeg = byteArrayOf(1, 2, 3)
        val d = ScanDraftBuilder.draft(ObjectKind.BOX, v, jpeg, "Carton", DeviceTier.DEPTH, "1.57.0")
        assertEquals(ObjectKind.BOX, d.kind)
        assertEquals(v.width, d.width)
        assertEquals(v.height, d.height)
        assertTrue(d.height.isEstimate)
        assertEquals(v.depth, d.depth)
        assertEquals(v.quality, d.quality)
        assertEquals(v.calibrated, d.calibrated)
        assertEquals(DeviceTier.DEPTH, d.deviceTier)
        assertArrayEquals(jpeg, d.frontJpeg)
        assertEquals("Carton", d.label)
        assertEquals(1.0, d.calibrationFactor!!, 0.0)
        assertNotNull(d.arcoreVersion)
    }

    /** Preset depth on a frame: the diagonal of measured W and H keeps its ±; a typed side makes it TYPED. */
    @Test
    fun diagonalOfFollowsItsSides() {
        val w = Dimension.measured(0.6, 0.012)
        val h = Dimension.measured(0.4, 0.011)
        assertEquals(DimensionSource.MEASURED, ScanDraftBuilder.diagonalOf(w, h).source)
        assertEquals(DimensionSource.TYPED, ScanDraftBuilder.diagonalOf(Dimension.typed(0.6), h).source)
        assertEquals(DimensionSource.PRESET, ScanDraftBuilder.diagonalOf(Dimension.preset(0.6), h).source)
        assertEquals(0.0, ScanDraftBuilder.diagonalOf(Dimension.typed(0.6), h).bound95, 0.0)
    }
}
