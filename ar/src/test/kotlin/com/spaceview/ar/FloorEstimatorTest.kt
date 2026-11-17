package com.spaceview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M4-P1 gate N1: the robust floor estimate behind Place's wall-height readout and wall–floor line. */
class FloorEstimatorTest {
    @Test fun lowestRealSurfaceWinsOverStrayPointsAndTables() {
        val f = FloorEstimator()
        assertNull(f.floorY())
        // A coffee table plane at −0.95 and the floor plane at −1.40; stray points below both, each at one spot.
        repeat(50) { f.add(0.1, -0.95, -1.0, onPlane = true) }
        repeat(100) { f.add(0.4, -1.49, -2.0, onPlane = false) }
        repeat(3) { f.add(-0.2, -1.43, -1.5, onPlane = false) }
        assertEquals(-0.95, f.floorY()!!, 1e-9)
        f.add(1.0, -1.40, -2.0, onPlane = true)
        assertEquals(-1.40, f.floorY()!!, 1e-9)
    }

    /**
     * T-D0 review MINOR 3: the mirrored top edge of a 45 cm TV unit on a glossy floor, swept by the reticle, is a spread
     * line of point hits 45 cm *below* the floor. A plane-backed floor wins over it.
     */
    @Test fun aPlaneBackedFloorWinsOverASpreadPointClusterBelowIt() {
        val f = FloorEstimator()
        f.add(0.0, -1.40, -2.0, onPlane = true)
        for (k in 0 until 12) f.add(-0.6 + 0.1 * k, -1.85, -2.5, onPlane = false)
        assertEquals(-1.40, f.floorY()!!, 1e-9)
        // Without any plane, the spread points are the best evidence there is.
        val g = FloorEstimator()
        for (k in 0 until 12) g.add(-0.6 + 0.1 * k, -1.85, -2.5, onPlane = false)
        assertEquals(-1.85, g.floorY()!!, 1e-9)
    }

    @Test fun aFloorStraddlingABinEdgeIsAveragedAndTheBinBelowIsIgnored() {
        val f = FloorEstimator()
        f.add(0.0, 0.0, 0.0, onPlane = true) // reference: bin edges at multiples of 2 cm
        repeat(10) { f.add(0.0, -1.399, 0.0, onPlane = true) }
        repeat(10) { f.add(0.0, -1.401, 0.0, onPlane = true) }
        repeat(40) { f.add(0.5, -1.425, 0.5, onPlane = false) } // a stray spot in the bin below
        assertEquals(-1.40, f.floorY()!!, 1e-9)
    }

    @Test fun confirmedTapsAreUsedOnlyUntilASurfaceQualifiesAndResetForgets() {
        val f = FloorEstimator()
        f.add(0.0, -1.5, 0.0, onPlane = false)
        f.confirm(-1.38)
        assertEquals(-1.38, f.floorY()!!, 1e-9)
        f.add(0.0, -1.40, 0.0, onPlane = true)
        assertEquals(-1.40, f.floorY()!!, 1e-9)
        f.reset()
        assertNull(f.floorY())
        // Hits more than ±5 m from the first one are not this room's floor; non-finite hits are ignored.
        f.add(0.0, 0.0, 0.0, onPlane = true)
        f.add(0.0, -6.0, 0.0, onPlane = true)
        f.add(Double.NaN, -1.0, 0.0, onPlane = true)
        assertEquals(0.0, f.floorY()!!, 1e-9)
    }
}
