package com.spaceview.ar

import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.Pose
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3.9 PlacementStateTest: the [PlacementCore] state machine. */
class PlacementStateTest {
    private val floorHit = SurfaceHit(Vec3(0.0, -1.4, -2.0), Vec3.UP)
    private val wallHit = SurfaceHit(Vec3(0.0, 0.0, -3.0), Vec3.UNIT_Z)
    private val camera = Pose(Vec3.ZERO)
    private var t = 0L

    private fun PlacementCore.frame(hit: SurfaceHit?, anchor: Pose? = null, tracking: Boolean = true, dtNs: Long = 33_000_000L): Boolean {
        t += dtNs
        return onFrame(t, tracking, camera, hit, anchor)
    }

    private fun sofa() = PlacementCore(PlacedItem(2.0, 0.85, 0.9, BoxOrigin.BOTTOM_CENTER), SurfaceTarget.FLOOR)
    private fun frameItem() = PlacementCore(PlacedItem(0.6, 0.4, 0.03, BoxOrigin.BACK_CENTER), SurfaceTarget.WALL)

    @Test fun searchingPreviewPlaced() {
        val c = sofa()
        assertEquals(PlacementState.Searching, c.status.state)
        c.frame(null)
        assertEquals(PlacementState.Searching, c.status.state)
        assertNull(c.modelPose)
        c.frame(wallHit) // wrong surface for a floor target
        assertEquals(PlacementState.Searching, c.status.state)
        c.frame(floorHit)
        val preview = c.status.state as PlacementState.Preview
        assertEquals(SurfaceTarget.FLOOR, preview.surface)
        assertTrue(preview.pose.translation.approxEquals(floorHit.position))
        val req = c.drop()!!
        assertTrue(req.surfacePose.translation.approxEquals(floorHit.position))
        c.onAnchored(true)
        // The anchor's pose drives the model from now on, wherever the reticle goes.
        val anchor = Pose(Vec3(0.01, -1.4, -2.0), req.surfacePose.rotation)
        c.frame(null, anchor)
        val placed = c.status.state as PlacementState.Placed
        assertTrue(placed.anchored && placed.tracking)
        assertTrue(c.modelPose!!.translation.approxEquals(Vec3(0.01, -1.4, -2.0), 1e-12))
    }

    @Test fun repeatedCameraTimestampsDoNotAdvanceState() {
        val c = sofa()
        assertTrue(c.frame(floorHit))
        val before = c.status
        // SceneView repeats the same camera frame at display rate: same timestamp, ignored entirely.
        assertFalse(c.onFrame(t, true, camera, null, null))
        assertEquals(before, c.status)
        assertNotNull(c.modelPose)
        // The 5 s wall-fallback timer also ignores repeated frames.
        val w = frameItem()
        w.frame(null)
        repeat(1000) { assertFalse(w.onFrame(t, true, camera, null, null)) }
        assertFalse(w.status.offerWallFallback)
    }

    @Test fun pauseGoesBackToSearchingKeepingTheItemAndResumes() {
        val c = sofa()
        c.frame(floorHit)
        c.rotate90()
        c.drop(); c.onAnchored(true)
        c.onPaused()
        assertEquals(PlacementState.Searching, c.status.state)
        assertNull(c.modelPose)
        assertEquals(SurfaceTarget.FLOOR, c.status.target)
        assertEquals(90, c.status.spinDeg)
        // Resume: frames again; the item previews with its spin kept.
        c.frame(floorHit)
        assertTrue(c.status.state is PlacementState.Preview)
        val front = c.modelPose!!.rotateVector(Vec3.UNIT_Z)
        // Front faces the camera (+Z here) turned by 90°: along +X or −X.
        assertEquals(1.0, kotlin.math.abs(front.x), 1e-9)
    }

    @Test fun trackingLossHidesThePlacedItem() {
        val c = sofa()
        c.frame(floorHit)
        val req = c.drop()!!
        c.onAnchored(true)
        c.frame(null, anchor = null) // anchor not tracked
        assertNull(c.modelPose)
        assertFalse((c.status.state as PlacementState.Placed).tracking)
        c.frame(null, anchor = req.surfacePose)
        assertNotNull(c.modelPose)
    }

    @Test fun failedAnchorAndPickUpReturnToFollowing() {
        val c = sofa()
        c.frame(floorHit)
        c.drop(); c.onAnchored(false)
        assertFalse(c.status.state is PlacementState.Placed)
        c.frame(floorHit)
        c.drop(); c.onAnchored(true)
        c.pickUp()
        c.frame(floorHit)
        assertTrue(c.status.state is PlacementState.Preview)
        assertNull("nothing to drop without a preview", sofa().drop())
    }

    @Test fun nudgeMovesOneCentimetreRelativeToTheAnchor() {
        val c = sofa()
        c.frame(floorHit)
        val req = c.drop()!!
        c.onAnchored(true)
        c.frame(null, req.surfacePose)
        val p0 = c.modelPose!!.translation
        c.nudge(1, 0)
        c.nudge(0, 2)
        val p1 = c.modelPose!!.translation
        assertTrue((p1 - p0).approxEquals(Vec3(0.01, 0.0, -0.02), 1e-12))
        // The offset follows a refined anchor.
        val moved = Pose(req.surfacePose.translation + Vec3(0.0, 0.0, 0.1), req.surfacePose.rotation)
        c.frame(null, moved)
        assertTrue((c.modelPose!!.translation - p1).approxEquals(Vec3(0.0, 0.0, 0.1), 1e-12))
    }

    @Test fun wallFallbackIsOfferedAfterFiveSecondsAndBuildsAPlane() {
        val c = frameItem()
        c.frame(floorHit) // the floor is seen, but no wall
        repeat(160) { c.frame(null) } // just over 5 s at 30 fps
        assertTrue(c.status.offerWallFallback)
        c.startFallback()
        assertEquals(PlacementState.FallbackLine(0), c.status.state)
        assertFalse("a wall is not the floor line", run { c.frame(wallHit); c.addFallbackPoint() })
        c.frame(SurfaceHit(Vec3(-0.5, -1.4, -3.0), Vec3.UP)); assertTrue(c.addFallbackPoint())
        assertEquals(PlacementState.FallbackLine(1), c.status.state)
        c.frame(SurfaceHit(Vec3(0.6, -1.4, -3.0), Vec3.UP)); assertTrue(c.addFallbackPoint())
        assertEquals(SurfaceTarget.WALL_FALLBACK, c.status.target)
        // The reticle ray (camera forward, −Z) meets the fallback wall at z = −3.
        c.frame(null)
        val preview = c.status.state as PlacementState.Preview
        assertEquals(-3.0, preview.pose.translation.z, 1e-9)
        assertEquals(1.4, c.status.centreHeightM!!, 1e-9) // centre at camera height, 1.4 m above the floor
        val req = c.drop()!!
        c.onAnchored(true)
        c.frame(null, req.surfacePose)
        assertTrue(c.setCentreHeight(1.45))
        assertEquals(1.45, c.status.centreHeightM!!, 1e-9)
        // Pause: the fallback plane (world coordinates) is dropped and offered again.
        c.onPaused()
        assertEquals(SurfaceTarget.WALL, c.status.target)
        assertTrue(c.status.offerWallFallback)
    }

    @Test fun tooShortFallbackLineIsRejected() {
        val c = frameItem()
        c.startFallback()
        c.frame(SurfaceHit(Vec3(0.0, -1.4, -3.0), Vec3.UP)); c.addFallbackPoint()
        c.frame(SurfaceHit(Vec3(0.1, -1.4, -3.0), Vec3.UP)); c.addFallbackPoint()
        assertTrue(c.status.fallbackRejected)
        assertEquals(PlacementState.FallbackLine(0), c.status.state)
        c.cancelFallback()
        assertEquals(PlacementState.Searching, c.status.state)
    }

    @Test fun targetChangePicksUpAndNewSessionForgetsTheFloor() {
        val c = frameItem()
        c.frame(wallHit)
        assertTrue(c.status.state is PlacementState.Preview)
        c.drop(); c.onAnchored(true)
        c.setTarget(SurfaceTarget.TABLE)
        assertEquals(PlacementState.Searching, c.status.state)
        c.frame(floorHit)
        assertTrue(c.status.state is PlacementState.Preview)
        c.onSessionCreated()
        assertNull(c.status.centreHeightM)
        assertFalse(c.status.cameraTracking)
        try {
            c.setTarget(SurfaceTarget.WALL_FALLBACK)
            throw AssertionError("fallback without a line")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun fallbackLineOnARaisedSurfaceIsRejectedOnceTheFloorIsKnown() {
        // M4 review MINOR 4: two taps on a coffee-table edge 45 cm above the floor must not become a wall.
        val c = frameItem()
        c.frame(floorHit) // the floor (y = −1.4) has been seen
        c.startFallback()
        c.frame(SurfaceHit(Vec3(-0.5, -0.95, -2.0), Vec3.UP)); c.addFallbackPoint()
        c.frame(SurfaceHit(Vec3(0.6, -0.95, -2.0), Vec3.UP)); c.addFallbackPoint()
        assertTrue(c.status.fallbackRejected)
        assertEquals(SurfaceTarget.WALL, c.status.target)
        // The same line on the floor works.
        c.frame(SurfaceHit(Vec3(-0.5, -1.4, -2.0), Vec3.UP)); c.addFallbackPoint()
        c.frame(SurfaceHit(Vec3(0.6, -1.39, -2.0), Vec3.UP)); c.addFallbackPoint()
        assertEquals(SurfaceTarget.WALL_FALLBACK, c.status.target)
    }

    @Test fun pausesAreCountedAndTheAllocationFreePathMatches() {
        val c = sofa()
        assertEquals(0, c.status.pauses)
        c.onPaused()
        assertEquals(1, c.status.pauses)
        c.onSessionCreated()
        assertEquals(2, c.status.pauses)
        // onFrame with a separate position and normal behaves like the SurfaceHit overload.
        t += 33_000_000L
        assertTrue(c.onFrame(t, true, camera, floorHit.position, floorHit.normal, null))
        assertTrue(c.state is PlacementState.Preview)
        assertTrue(c.cameraTrackingNow)
        t += 33_000_000L
        c.onFrame(t, true, camera, floorHit.position, null, null)
        assertEquals(PlacementState.Searching, c.state)
    }

    /**
     * M4-P1 gate N1: one mirrored feature point 6 cm below a polished floor (seen for a whole second at one spot) must
     * not become the floor. The old "lowest hit" rule then rejected every genuine wall–floor tap (> 3 cm above it).
     */
    @Test fun aMirroredPointBelowThePolishedFloorDoesNotRejectGenuineFloorTaps() {
        val c = frameItem()
        c.frame(floorHit) // the floor plane at y = −1.4
        repeat(30) { c.frame(SurfaceHit(Vec3(0.2, -1.46, -2.5), Vec3.UP, onPlane = false)) }
        c.startFallback()
        c.frame(SurfaceHit(Vec3(-0.5, -1.4, -3.0), Vec3.UP)); assertTrue(c.addFallbackPoint())
        c.frame(SurfaceHit(Vec3(0.6, -1.39, -3.0), Vec3.UP)); assertTrue(c.addFallbackPoint())
        assertFalse(c.status.fallbackRejected)
        assertEquals(SurfaceTarget.WALL_FALLBACK, c.status.target)
        // The wall height readout uses the real floor: the centre at camera height is 1.4 m above it.
        c.frame(null)
        assertEquals(1.4, c.status.centreHeightM!!, 0.01)
    }

    /** N1: point / depth hits alone make a floor once they are many and spread out (a plain floor with no plane yet). */
    @Test fun spreadPointHitsMakeAFloorButOneSpotDoesNot() {
        val c = frameItem()
        c.frame(wallHit)
        c.drop(); c.onAnchored(true)
        repeat(20) { c.frame(SurfaceHit(Vec3(0.3, -1.5, -2.0), Vec3.UP, onPlane = false), anchor = null) }
        assertNull("one spot is not a floor", c.status.centreHeightM)
        for (k in 0 until 10) c.frame(SurfaceHit(Vec3(-0.5 + 0.1 * k, -1.4, -2.0 - 0.05 * k), Vec3.UP, onPlane = false))
        // Placed on the wall at y = 0 with a 0.4 m tall item centred there: 1.4 m above the spread floor.
        assertEquals(1.4, c.status.centreHeightM!!, 0.011)
    }

    /** M4-P1 gate N5: a steady placed item keeps the same pose instance (nothing rebuilt, nothing re-set on the node). */
    @Test fun aSteadyPlacedItemKeepsItsPoseInstance() {
        val c = sofa()
        c.frame(floorHit)
        val req = c.drop()!!
        c.onAnchored(true)
        val anchor = Pose(req.surfacePose.translation, req.surfacePose.rotation)
        c.frame(null, anchor)
        val p = c.modelPose!!
        val state = c.state
        repeat(10) { c.frame(null, anchor) }
        assertTrue("same pose instance", c.modelPose === p)
        assertTrue("same state instance", c.state === state)
        // A refined anchor or a command rebuilds it.
        c.frame(null, Pose(anchor.translation + Vec3(0.0, 0.0, 0.01), anchor.rotation))
        assertFalse(c.modelPose === p)
        val q = c.modelPose!!
        c.rotate90()
        assertFalse(c.modelPose === q)
    }
}
