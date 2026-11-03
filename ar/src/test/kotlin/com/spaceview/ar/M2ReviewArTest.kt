package com.spaceview.ar

import com.spaceview.engine.DeviceTier
import com.spaceview.engine.LengthUnit
import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Plane
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Ray
import com.spaceview.engine.ScaleCalibration
import com.spaceview.engine.TrackingQuality
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Units
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Regression tests for docs/reviews/M2-review.md, AR side. */
class M2ReviewArTest {

    // ---- B1: pixel units --------------------------------------------------------------------------

    private val vga = ImageIntrinsics(500.0, 640, 480)

    /** An endpoint built exactly as MeasureSession's worker builds it (0.3 mm jitter SE + aim term). */
    private fun endpoint(p: Vec3, range: Double, intr: ImageIntrinsics, viewW: Int, viewH: Int): EndpointData {
        val loc = AimModel.aim(range, intr, AimModel.viewPxPerImagePx(intr, viewW, viewH))!!
        return EndpointData(
            UncertainPoint.of(p, 0.0003).withLocalisation(loc), Vec3.UNIT_Z, HitKind.FEATURE_POINT, range,
            focalPx = loc.focalLengthPx, pixelErrorPx = loc.pixelErrorPx,
        )
    }

    private fun cardReading(intr: ImageIntrinsics, viewW: Int, viewH: Int): Reading {
        val a = endpoint(Vec3(0.0, 0.0, -0.3), 0.3, intr, viewW, viewH)
        val b = endpoint(Vec3(0.0856, 0.0, -0.3), 0.3, intr, viewW, viewH)
        return MeasurementComposer.compose(MeasureMode.DISTANCE, listOf(a, b), null, UncertaintyModel(DeviceTier.NO_DEPTH))!!
    }

    @Test fun bankCardAt30cmIsAtLeastAboutOneCentimetreOnEveryScreen() {
        val fhd = cardReading(vga, 1080, 2400)
        val qhd = cardReading(vga, 1440, 3200)
        val hd = cardReading(vga, 720, 1600)
        // Independent of the display: same camera, same ±.
        assertEquals(fhd.primary, qhd.primary)
        assertEquals(fhd.primary, hd.primary)
        // … and of the CPU-image resolution ARCore picked (the aim error is an angle in VGA pixels).
        val hdCamera = cardReading(ImageIntrinsics(1000.0, 1280, 720), 1440, 3200)
        assertEquals(fhd.primary.sigma, hdCamera.primary.sigma, 1e-4 * fhd.primary.sigma)
        for (r in listOf(fhd, qhd, hdCamera)) {
            assertTrue("±${r.primary.bound95}", r.primary.bound95 >= 0.0099)
            val shown = Units.formatForContext(r.primary, MeasureContext.SMALL_OBJECT).primary
            assertEquals(LengthUnit.CM, shown.unit)
            assertTrue(shown.text, shown.plusMinus!!.toDouble() >= 1.0)
            assertEquals(com.spaceview.engine.Quality.ROUGH, r.quality) // the probe showed GOOD
        }
    }

    @Test fun aimAndAdjustAreInCameraImagePixels() {
        assertEquals(3.75, AimModel.viewPxPerImagePx(vga, 1080, 2400), 1e-12)
        assertEquals(3.75, AimModel.viewPxPerImagePx(vga, 2400, 1080), 1e-12) // landscape view
        val aim = AimModel.aim(1.0, vga, 3.75)!!
        assertEquals(AimModel.AIM_PIXELS_VGA, aim.pixelErrorPx, 0.0)
        assertEquals(0.004, aim.sigmaMeters, 1e-12) // 2 VGA px at 1 m, f = 500
        // A 1280-wide CPU image is normalised to the VGA reference: same angle.
        assertEquals(0.004, AimModel.aim(1.0, ImageIntrinsics(1000.0, 1280, 720), 2.0)!!.sigmaMeters, 1e-12)
        // A view coarser than the image makes the view-pixel error dominate.
        val coarse = AimModel.aim(1.0, vga, 0.5)!!
        assertEquals(4.0, coarse.pixelErrorPx, 1e-12)
        assertNull(AimModel.aim(0.0, vga))
        assertNull(AimModel.aim(Double.NaN, vga))
        // Freeze-frame: one pixel of the still, with the still's own focal length (not view pixels).
        val adj = AimModel.adjust(0.5, vga, 3.75)!!
        assertEquals(500.0, adj.focalLengthPx, 0.0)
        assertEquals(1.0, adj.pixelErrorPx, 0.0)
        assertEquals(0.001, adj.sigmaMeters, 1e-12)
        // A still shown shrunk (fewer view pixels than image pixels): the drag error in image pixels grows.
        assertEquals(2.0, AimModel.adjust(0.5, vga, 0.5)!!.pixelErrorPx, 1e-12)
        assertNull(AimModel.adjust(-1.0, vga, 1.0))
        assertEquals(640, vga.longSidePx)
        assertEquals(480, vga.shortSidePx)
    }

    @Test(expected = IllegalArgumentException::class)
    fun intrinsicsMustBeValid() { ImageIntrinsics(0.0, 640, 480) }

    // ---- M2: calibration only on the calibrated plane ---------------------------------------------

    private val table = Plane.fromPointNormal(Vec3(0.0, 0.75, 0.0), Vec3.UP)
    private val calibrated = UncertaintyModel(DeviceTier.NO_DEPTH)
        .withCalibration(ScaleCalibration(1.03, 0.001, plane = table, anchor = Vec3(0.0, 0.75, -0.5), rangeM = 0.5))

    private fun ep(p: Vec3, normal: Vec3?, range: Double = 0.6) =
        EndpointData(UncertainPoint.of(p, 0.001).withLocalisationSigma(0.001), normal, HitKind.PLANE, range)

    @Test fun floorToTableHeightIsUncalibrated() {
        val r = MeasurementComposer.compose(
            MeasureMode.HEIGHT, listOf(ep(Vec3(0.0, 0.0, -0.6), Vec3.UP), ep(Vec3(0.0, 0.75, -0.6), Vec3.UP)), null, calibrated,
        )!!
        assertFalse(r.calibrated)
        assertEquals(0.75, r.primary.value, 1e-4) // factor not applied
        val plain = MeasurementComposer.compose(
            MeasureMode.HEIGHT, listOf(ep(Vec3(0.0, 0.0, -0.6), Vec3.UP), ep(Vec3(0.0, 0.75, -0.6), Vec3.UP)), null,
            UncertaintyModel(DeviceTier.NO_DEPTH),
        )!!
        assertEquals(plain.primary, r.primary) // and no tighter ±
    }

    @Test fun floorToWallIsUncalibrated() {
        val r = MeasurementComposer.compose(
            MeasureMode.DISTANCE, listOf(ep(Vec3(0.2, 0.0, -1.0), Vec3.UP), ep(Vec3(0.2, 1.0, -1.5), Vec3.UNIT_Z)), null, calibrated,
        )!!
        assertFalse(r.calibrated)
    }

    @Test fun pointsOnTheCalibratedTableAreCalibrated() {
        val r = MeasurementComposer.compose(
            MeasureMode.DISTANCE, listOf(ep(Vec3(-0.2, 0.75, -0.5), Vec3.UP), ep(Vec3(0.3, 0.75, -0.5), Vec3.UP)), null, calibrated,
        )!!
        assertTrue(r.calibrated)
        assertEquals(0.5 * 1.03, r.primary.value, 1e-4)
        // A rectangle with every corner on the table is calibrated; one corner on the floor is not.
        val rect = listOf(ep(Vec3(-0.2, 0.75, -0.4), Vec3.UP), ep(Vec3(0.2, 0.75, -0.4), Vec3.UP), ep(Vec3(0.2, 0.75, -0.7), Vec3.UP))
        assertTrue(MeasurementComposer.compose(MeasureMode.RECTANGLE, rect, null, calibrated)!!.calibrated)
        val mixed = rect.dropLast(1) + ep(Vec3(0.2, 0.0, -0.7), Vec3.UP)
        assertFalse(MeasurementComposer.compose(MeasureMode.RECTANGLE, mixed, null, calibrated)!!.calibrated)
    }

    // ---- M4: the fitted surface may only refine the nearest hit -----------------------------------

    private val camera = Vec3(0.0, 1.2, 0.0)
    private val toBoxTop = Ray(camera, Vec3(0.0, 0.4, -0.8) - camera) // 45° down, box top at 1.13 m

    /** A 40 cm box (top y = 0.4, 12 × 12 cm around z = −0.8) on a textured floor (y = 0). */
    private fun boxOnFloor(model: LocalSurfaceModel, boxPoints: Int = 40, floorPoints: Int = 240) {
        val rng = Random(11)
        val pts = FloatArray(3 * (boxPoints + floorPoints))
        var k = 0
        repeat(boxPoints) {
            pts[k++] = (rng.nextDouble() * 0.12 - 0.06).toFloat(); pts[k++] = (0.4 + rng.nextGaussian() * 0.001).toFloat()
            pts[k++] = (-0.8 + rng.nextDouble() * 0.12 - 0.06).toFloat()
        }
        // Floor points around where the reticle ray would continue to (0, 0, −1.2).
        repeat(floorPoints) {
            pts[k++] = (rng.nextDouble() * 0.3 - 0.15).toFloat(); pts[k++] = (rng.nextGaussian() * 0.001).toFloat()
            pts[k++] = (-1.2 + rng.nextDouble() * 0.3 - 0.15).toFloat()
        }
        model.addPoints(pts, boxPoints + floorPoints, 0L)
    }

    private val boxTopHit = HitSample(Vec3(0.0, 0.4, -0.8), Vec3.UP, HitKind.FEATURE_POINT, Vec3(0.0, 0.4, -0.8).distanceTo(camera))

    @Test fun boxTopIsNotReplacedByTheFloorBehindIt() {
        // Without a hit the floor dominates the cone (the review's failure: the fit wins).
        val noHint = LocalSurfaceModel()
        boxOnFloor(noHint)
        val floorFit = SurfaceChooser.choose(null, noHint, toBoxTop, camera, 1L)!!
        assertEquals(0.0, floorFit.position.y, 0.01)

        // With the feature hit on the box top, the depth band keeps only box-top points: the fit refines it.
        val model = LocalSurfaceModel()
        boxOnFloor(model)
        val chosen = SurfaceChooser.choose(boxTopHit, model, toBoxTop, camera, 1L)!!
        assertEquals(HitKind.FITTED_SURFACE, chosen.kind)
        assertEquals(0.4, chosen.position.y, 0.005)
        assertEquals(boxTopHit.distance, chosen.range, SurfaceChooser.tolerance(boxTopHit.distance))
    }

    @Test fun inconsistentFitFallsBackToTheNearerHit() {
        // No box points in the cloud at all, and the depth band disabled: the only fit is the floor,
        // 57 cm behind the hit along the ray. The consistency check must keep the hit.
        val model = LocalSurfaceModel(LocalSurfaceConfig(depthBandMin = 10.0))
        boxOnFloor(model, boxPoints = 0)
        val chosen = SurfaceChooser.choose(boxTopHit, model, toBoxTop, camera, 1L)!!
        assertEquals(HitKind.FEATURE_POINT, chosen.kind)
        assertEquals(0.4, chosen.position.y, 1e-9)
        // With the band (shipped config) the floor points are not even fitted.
        val gated = LocalSurfaceModel()
        boxOnFloor(gated, boxPoints = 0)
        assertNull(gated.update(toBoxTop, 1L, boxTopHit.distance))
        assertEquals(HitKind.FEATURE_POINT, SurfaceChooser.choose(boxTopHit, gated, toBoxTop, camera, 2L)!!.kind)
    }

    @Test fun planeHitsAndMissingSurfaces() {
        val plane = HitSample(Vec3(0.0, 0.0, -1.2), Vec3.UP, HitKind.PLANE, 1.7)
        val c = SurfaceChooser.choose(plane, LocalSurfaceModel(), toBoxTop, camera, 1L)!!
        assertEquals(HitKind.PLANE, c.kind)
        assertEquals(0.0, c.planeBiasRel, 0.0)
        assertNull(SurfaceChooser.choose(null, LocalSurfaceModel(), toBoxTop, camera, 1L))
        assertEquals(0.02, SurfaceChooser.tolerance(0.3), 0.0)
        assertEquals(0.06, SurfaceChooser.tolerance(2.0), 1e-12)
    }

    // ---- minor 6: the buffer keeps the points near the reticle ------------------------------------

    @Test fun farPointsDoNotFlushTheBuffer() {
        val model = LocalSurfaceModel(LocalSurfaceConfig(maxPoints = 64))
        val ray = Ray(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -1.0))
        val rng = Random(3)
        // 40 wall points near the reticle (z = −2) …
        val near = FloatArray(3 * 40)
        for (i in 0 until 40) {
            near[i * 3] = (rng.nextDouble() * 0.2 - 0.1).toFloat(); near[i * 3 + 1] = (1.0 + rng.nextDouble() * 0.2 - 0.1).toFloat(); near[i * 3 + 2] = -2f
        }
        model.addPoints(near, 40, 0L, ray)
        // … then a big cloud far from the ray (another wall, behind the camera, off to the side).
        val far = FloatArray(3 * 2000)
        for (i in 0 until 2000) {
            far[i * 3] = (3.0 + rng.nextDouble()).toFloat(); far[i * 3 + 1] = rng.nextDouble().toFloat(); far[i * 3 + 2] = (-rng.nextDouble() * 4).toFloat()
        }
        model.addPoints(far, 2000, 0L, ray)
        assertNotNull(model.update(ray, 1L))
        assertEquals(-2.0, model.intersect(ray, ray.origin)!!.position.z, 0.002)
        // Without the ray filter the same cloud would overwrite the 64-point buffer.
        val unfiltered = LocalSurfaceModel(LocalSurfaceConfig(maxPoints = 64))
        unfiltered.addPoints(near, 40, 0L)
        unfiltered.addPoints(far, 2000, 0L)
        assertNull(unfiltered.update(ray, 1L))
    }

    // ---- minor 4: the stabiliser and tracking loss ------------------------------------------------

    private fun steady(s: SampleStabilizer, n: Int) = repeat(n) { s.add(Vec3(0.0, 0.0, -1.0), 1.0, TrackingQuality.TRACKING) }

    @Test fun shortTrackingLossResetsAtOnce() {
        val s = SampleStabilizer()
        steady(s, 20)
        assertNotNull(s.estimate)
        // One NOT_TRACKING frame without a position (the worker skips the hit) must reset everything.
        s.add(null, 0.0, TrackingQuality.NOT_TRACKING)
        assertNull(s.estimate)
        assertEquals(0.0, s.progress, 0.0)
        // Warm-up after the loss (LIMITED) recomputes nothing from pre-loss samples.
        s.add(Vec3(0.0, 0.0, -1.0), 1.0, TrackingQuality.LIMITED)
        assertNull(s.estimate)
        assertFalse(s.isLocked)
    }

    @Test fun limitedExposesNoCommitCandidate() {
        val s = SampleStabilizer()
        steady(s, 20)
        assertTrue(s.isLocked)
        s.add(Vec3(0.0, 0.0, -1.0), 1.0, TrackingQuality.LIMITED)
        assertNull(s.estimate)
        assertFalse(s.isLocked)
        s.add(null, 0.0, TrackingQuality.LIMITED)
        assertNull(s.estimate)
        // Back to full tracking: the window was kept, so the estimate returns at once.
        s.add(Vec3(0.0, 0.0, -1.0), 1.0, TrackingQuality.TRACKING)
        assertNotNull(s.estimate)
    }

    // ---- minor 8: allocation-free depth back-projection matches the pose maths ----------------------

    @Test fun depthPatchRotatesLikeThePose() {
        val pose = Pose(Vec3(0.3, 1.1, -0.2), Quaternion.fromAxisAngle(Vec3(0.2, 1.0, -0.3), 0.8))
        val patch = DepthPatch(3, 3, 10, 20, ShortArray(9) { (1000 + it * 37).toShort() }, ByteArray(9) { 255.toByte() },
            fx = 120.0, fy = 118.0, cx = 80.5, cy = 60.5, sensorPose = pose)
        val out = FloatArray(27)
        assertEquals(9, patch.toWorldPoints(out))
        for (r in 0 until 3) for (c in 0 until 3) {
            val i = r * 3 + c
            val d = (1000 + i * 37) / 1000.0
            val local = Vec3(d * (10 + c + 0.5 - 80.5) / 120.0, d * (60.5 - (20 + r + 0.5)) / 118.0, -d)
            val w = pose.transformPoint(local)
            assertEquals(w.x, out[i * 3].toDouble(), 1e-5)
            assertEquals(w.y, out[i * 3 + 1].toDouble(), 1e-5)
            assertEquals(w.z, out[i * 3 + 2].toDouble(), 1e-5)
        }
    }
}
