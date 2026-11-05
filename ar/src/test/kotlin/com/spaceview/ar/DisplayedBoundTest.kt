package com.spaceview.ar

import com.spaceview.engine.AreaUnit
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Measurement
import com.spaceview.engine.Plane
import com.spaceview.engine.ScaleCalibration
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.Units
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * M2 gate R1: the ± the user reads is never below the engine's unrounded 95% bound, after both the
 * composer's value-equality rounding and the display rounding. Random readings (every mode and tier,
 * half with an in-scope calibration, worker-style endpoints) × every context × every preference.
 */
class DisplayedBoundTest {

    /** "1.4", "3/8", "1 1/2", "2″" → number in the display unit. */
    private fun parsePlusMinus(text: String): Double =
        text.removeSuffix("″").trim().split(' ').sumOf { part ->
            if ('/' in part) part.substringBefore('/').toDouble() / part.substringAfter('/').toDouble() else part.toDouble()
        }

    private fun assertShownCovers(shown: Measurement, engine: Measurement, what: String) {
        assertTrue("$what: rounding shrank σ ${engine.sigma} → ${shown.sigma}", shown.sigma >= engine.sigma)
        for (ctx in MeasureContext.entries) for (pref in UnitPreference.entries) {
            val f = Units.formatForContext(shown, ctx, pref).primary
            val pm = parsePlusMinus(f.plusMinus!!)
            val bound = Units.fromMetres(engine.bound95, f.unit)
            assertTrue("$what $ctx/$pref: ${f.text} < engine ±$bound", pm >= bound * (1 - 1e-9))
        }
    }

    @Test
    fun displayedPlusMinusNeverBelowUnroundedEngineBound() {
        val rng = Random(2026)
        val camera = ImageIntrinsics(500.0, 640, 480)
        var checked = 0
        repeat(2000) { trial ->
            val tier = DeviceTier.entries[trial % DeviceTier.entries.size]
            val mode = MeasureMode.entries[trial / 3 % MeasureMode.entries.size]
            val wallZ = -(0.4 + rng.nextDouble() * 2.5)
            val camPos = Vec3(0.0, 1.2, 0.0)
            fun endpoint(x: Double, y: Double): EndpointData {
                val p = Vec3(x, y, wallZ)
                val range = camPos.distanceTo(p)
                val loc = AimModel.aim(range, camera, 3.75)!!
                val jitter = 0.0001 + rng.nextDouble() * 0.002
                return EndpointData(
                    UncertainPoint.of(p, jitter).withLocalisation(loc), Vec3.UNIT_Z, HitKind.PLANE, range,
                    planeBiasRel = if (rng.nextBoolean()) rng.nextDouble() * 0.004 else 0.0,
                    focalPx = loc.focalLengthPx, pixelErrorPx = loc.pixelErrorPx,
                )
            }
            val w = 0.02 + rng.nextDouble() * 3
            val h = 0.02 + rng.nextDouble() * 2
            val pts = when (mode) {
                MeasureMode.DISTANCE -> listOf(endpoint(-w / 2, 1.0), endpoint(w / 2, 1.0 + rng.nextDouble() * 0.2))
                MeasureMode.HEIGHT -> listOf(endpoint(0.1, 0.2), endpoint(0.12, 0.2 + h))
                MeasureMode.RECTANGLE -> listOf(endpoint(-w / 2, 1.0 + h), endpoint(w / 2, 1.0 + h), endpoint(w / 2, 1.0))
                // A real box: base corners on the floor (normal up), then a point on the top.
                MeasureMode.BOX -> {
                    fun floorPoint(x: Double, y: Double, z: Double, n: Vec3): EndpointData {
                        val p = Vec3(x, y, z)
                        val range = camPos.distanceTo(p)
                        val loc = AimModel.aim(range, camera, 3.75)!!
                        return EndpointData(
                            UncertainPoint.of(p, 0.0001 + rng.nextDouble() * 0.002).withLocalisation(loc), n, HitKind.PLANE, range,
                            focalPx = loc.focalLengthPx, pixelErrorPx = loc.pixelErrorPx,
                        )
                    }
                    val d = 0.1 + rng.nextDouble() * 1.0
                    val z0 = wallZ / 2
                    listOf(
                        floorPoint(-w / 2, 0.0, z0, Vec3.UP), floorPoint(w / 2, 0.0, z0, Vec3.UP),
                        floorPoint(w / 2, 0.0, z0 - d, Vec3.UP), floorPoint(0.0, 0.05 + h, z0 - d / 2, Vec3.UP),
                    )
                }
            }
            var model = UncertaintyModel(tier)
            if (trial % 2 == 0) {
                // In scope: on the wall plane, at a similar range.
                val range = pts.map { it.rangeM }.average()
                val cal = ScaleCalibration(0.97 + rng.nextDouble() * 0.06, 0.001 + rng.nextDouble() * 0.003,
                    plane = Plane.fromPointNormal(Vec3(0.0, 0.0, wallZ), Vec3.UNIT_Z), anchor = Vec3(0.0, 1.0, wallZ), rangeM = range)
                model = model.withCalibration(cal)
            }
            val shown = MeasurementComposer.compose(mode, pts, null, model) ?: return@repeat
            val engine = MeasurementComposer.compose(mode, pts, null, model, rounding = false)!!
            assertEquals(engine.calibrated, shown.calibrated)
            assertShownCovers(shown.primary, engine.primary, "$mode primary")
            checked++
            val sh = shown.secondary
            val eh = engine.secondary
            if (sh != null && eh != null) assertShownCovers(sh, eh, "$mode secondary")
            val sb = shown.boxHeight
            val eb = engine.boxHeight
            if (sb != null && eb != null) assertShownCovers(sb, eb, "$mode box height")
            val sd = shown.diagonal
            val ed = engine.diagonal
            if (sd != null && ed != null) assertShownCovers(sd, ed, "$mode diagonal")
            val sa = shown.area
            val ea = engine.area
            if (sa != null && ea != null) {
                assertTrue(sa.sigma >= ea.sigma)
                for (au in AreaUnit.entries) {
                    val text = Units.formatArea(sa, au)
                    assertTrue("$au $text", text.substringAfter("± ").toDouble() >= ea.bound95 / au.squareMetres * (1 - 1e-9))
                }
            }
        }
        assertTrue(checked > 1900)
    }
}
