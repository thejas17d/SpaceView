package com.spaceview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** M4-P1 gate N1: the robust floor estimate behind Place's wall-height readout and wall–floor line. */
class FloorEstimatorTest {
    /** Camera frames at 30 fps: the k-th hit is in frame k. */
    private var frame = 0L
    private fun FloorEstimator.hit(x: Double, y: Double, z: Double, onPlane: Boolean) =
        add(x, y, z, onPlane, (frame++) * FRAME_NS)

    @Test fun lowestRealSurfaceWinsOverStrayPointsAndTables() {
        val f = FloorEstimator()
        assertNull(f.floorY())
        // A coffee table plane at −0.95 and the floor plane at −1.40; stray points below both, each at one spot.
        repeat(50) { f.hit(0.1, -0.95, -1.0, onPlane = true) }
        repeat(100) { f.hit(0.4, -1.49, -2.0, onPlane = false) }
        repeat(3) { f.hit(-0.2, -1.43, -1.5, onPlane = false) }
        assertEquals(-0.95, f.floorY()!!, 1e-9)
        f.hit(1.0, -1.40, -2.0, onPlane = true)
        assertEquals(-1.40, f.floorY()!!, 1e-9)
    }

    /**
     * T-D0 review MINOR 3: the mirrored top edge of a 45 cm TV unit on a glossy floor, swept by the reticle, is a spread
     * line of point hits 45 cm *below* the floor. A plane-backed floor wins over it.
     */
    @Test fun aPlaneBackedFloorWinsOverASpreadPointClusterBelowIt() {
        val f = FloorEstimator()
        f.hit(0.0, -1.40, -2.0, onPlane = true)
        for (k in 0 until 12) f.hit(-0.6 + 0.1 * k, -1.85, -2.5, onPlane = false)
        assertEquals(-1.40, f.floorY()!!, 1e-9)
        // Without any plane, the spread points are the best evidence there is.
        val g = FloorEstimator()
        for (k in 0 until 12) g.hit(-0.6 + 0.1 * k, -1.85, -2.5, onPlane = false)
        assertEquals(-1.85, g.floorY()!!, 1e-9)
    }

    /**
     * G2 must not undo MINOR 3: the mirrored edge, swept back and forth for 10 s (300 hits, 1.2 m long, *diagonal* so
     * its x and z extents are both large), is still a line, and the mirrored reflection of a lamp is a spot. Neither
     * beats the plane floor, however long it is watched.
     */
    @Test fun aLongWatchedMirroredLineOrSpotNeverBeatsAPlaneFloor() {
        val f = FloorEstimator()
        repeat(20) { f.hit(0.0, -1.40, -2.0, onPlane = true) }
        for (k in 0 until 300) {
            val t = (k % 60) / 59.0 // 0..1, back and forth along the edge
            f.hit(-0.4 + 0.85 * t, -1.85 + jitter(k, 0.004), -2.0 - 0.85 * t + jitter(k + 7, 0.01), onPlane = false)
        }
        assertEquals(-1.40, f.floorY()!!, 1e-9)
        repeat(300) { k -> f.hit(0.3 + jitter(k, 0.01), -1.71, -1.2 + jitter(k + 3, 0.01), onPlane = false) }
        assertEquals(-1.40, f.floorY()!!, 1e-9)
    }

    /**
     * T-D0 gate G2: a glossy vitrified floor that ARCore never turns into a plane, and a textured coffee table 45 cm
     * up that it does. The reticle wanders over the floor for 4 s (depth / feature hits over a 1.2 × 0.9 m patch), so
     * the floor bin has strong support (count, area, time) and wins over the higher table plane.
     */
    @Test fun aStronglySupportedPointFloorBelowATablePlaneWins() {
        val f = FloorEstimator()
        // The table: a 0.8 × 0.5 m top the reticle wanders over.
        for (k in 0 until 40) f.hit(-0.2 + 0.8 * ((k * 7) % 11) / 10.0, -0.95, -1.25 + 0.5 * ((k * 5) % 9) / 8.0, onPlane = true)
        assertEquals(-0.95, f.floorY()!!, 1e-9)
        // The first second of floor hits: not yet seen long enough, the table still stands.
        var k = 0
        repeat(30) { floorHit(f, k++) }
        assertEquals(-0.95, f.floorY()!!, 1e-9)
        // Three more seconds over the same patch: strong. The floor is at −1.40 (± the hits' noise).
        repeat(90) { floorHit(f, k++) }
        assertEquals(-1.40, f.floorY()!!, 0.01)
    }

    /**
     * SM1 review MINOR 15: on a mirror-polished floor that ARCore *does* track, a 1.6 × 0.9 m dining table is reflected
     * 0.75 m below it. Its hits are many, 2-D and seen for 4 s, but they cover less area than the floor plane the user
     * has aimed over, so the floor plane stays the floor.
     */
    @Test fun aReflectedTableBelowATrackedFloorPlaneNeverWins() {
        val f = FloorEstimator()
        for (k in 0 until 120) f.hit(-1.5 + 3.0 * ((k * 37) % 61) / 60.0, -1.40, -3.0 + 2.5 * ((k * 23) % 47) / 46.0, onPlane = true)
        for (k in 0 until 120) f.hit(-0.8 + 1.6 * ((k * 13) % 29) / 28.0, -2.15, -2.2 + 0.9 * ((k * 17) % 31) / 30.0, onPlane = false)
        assertEquals(-1.40, f.floorY()!!, 1e-9)
    }

    /** G2: strong support needs time; 120 floor hits in one burst (< 2 s of camera time) do not beat the plane. */
    @Test fun aBurstOfFloorHitsIsNotConsistentOverTime() {
        val f = FloorEstimator()
        repeat(40) { f.add(0.2, -0.95, -1.0, onPlane = true, timestampNs = 0L) }
        for (k in 0 until 120) {
            val a = k * 2.399 // golden-angle spiral over a 0.6 m disc
            val r = 0.6 * kotlin.math.sqrt((k + 0.5) / 120)
            f.add(r * cos(a), -1.40, -2.0 + r * sin(a), onPlane = false, timestampNs = k * 10_000_000L) // 1.2 s
        }
        assertEquals(-0.95, f.floorY()!!, 1e-9)
    }

    @Test fun aFloorStraddlingABinEdgeIsAveragedAndTheBinBelowIsIgnored() {
        val f = FloorEstimator()
        f.hit(0.0, 0.0, 0.0, onPlane = true) // reference: bin edges at multiples of 2 cm
        repeat(10) { f.hit(0.0, -1.399, 0.0, onPlane = true) }
        repeat(10) { f.hit(0.0, -1.401, 0.0, onPlane = true) }
        repeat(40) { f.hit(0.5, -1.425, 0.5, onPlane = false) } // a stray spot in the bin below
        assertEquals(-1.40, f.floorY()!!, 1e-9)
    }

    @Test fun confirmedTapsAreUsedOnlyUntilASurfaceQualifiesAndResetForgets() {
        val f = FloorEstimator()
        f.hit(0.0, -1.5, 0.0, onPlane = false)
        f.confirm(-1.38)
        assertEquals(-1.38, f.floorY()!!, 1e-9)
        f.hit(0.0, -1.40, 0.0, onPlane = true)
        assertEquals(-1.40, f.floorY()!!, 1e-9)
        f.reset()
        assertNull(f.floorY())
        // Hits more than ±5 m from the first one are not this room's floor; non-finite hits are ignored.
        f.hit(0.0, 0.0, 0.0, onPlane = true)
        f.hit(0.0, -6.0, 0.0, onPlane = true)
        f.hit(Double.NaN, -1.0, 0.0, onPlane = true)
        assertEquals(0.0, f.floorY()!!, 1e-9)
    }

    /** One floor hit of a reticle wandering over a 1.2 × 0.9 m patch at −1.40 ± 6 mm (deterministic). */
    private fun floorHit(f: FloorEstimator, k: Int) {
        val u = ((k * 37) % 61) / 60.0
        val v = ((k * 23) % 47) / 46.0
        f.hit(-0.6 + 1.2 * u, -1.40 + jitter(k, 0.006), -2.4 + 0.9 * v, onPlane = false)
    }

    /** Deterministic noise in ±[amp]. */
    private fun jitter(k: Int, amp: Double) = amp * sin(k * 12.9898)

    private companion object {
        const val FRAME_NS = 33_333_333L
    }
}
