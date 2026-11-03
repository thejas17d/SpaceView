package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.TrackingQuality
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingGateTest {
    private val frameNs = 33_333_333L

    private fun still(gate: TrackingGate, frames: Int, startNs: Long = 0L): GateResult {
        var r: GateResult? = null
        for (i in 0 until frames) {
            r = gate.update(startNs + i * frameNs, CameraTracking.TRACKING, FailureReason.NONE, Pose.IDENTITY, 0.5f)
        }
        return r!!
    }

    @Test
    fun initialisingUntilFirstTracking() {
        val gate = TrackingGate()
        val r = gate.update(0, CameraTracking.PAUSED, FailureReason.NONE, Pose.IDENTITY, 0.5f)
        assertEquals(TrackingStatus.INITIALIZING, r.status)
        assertEquals(TrackingQuality.NOT_TRACKING, r.sampleQuality)
    }

    @Test
    fun warmUpHoldsSamplesBackThenAccepts() {
        val gate = TrackingGate()
        assertEquals(TrackingQuality.LIMITED, still(gate, 3).sampleQuality)
        assertEquals(TrackingQuality.TRACKING, still(gate, 30).sampleQuality)
        assertEquals(TrackingStatus.GOOD, still(gate, 30).status)
    }

    @Test
    fun fastRotationIsRejectedAndReported() {
        val gate = TrackingGate()
        still(gate, 30)
        var r: GateResult? = null
        // 2 rad/s about Y.
        for (i in 0 until 15) {
            val q = Quaternion.fromAxisAngle(Vec3.UP, 2.0 * i * frameNs / 1e9)
            r = gate.update(30 * frameNs + i * frameNs, CameraTracking.TRACKING, FailureReason.NONE, Pose(Vec3.ZERO, q), 0.5f)
        }
        assertEquals(TrackingStatus.TOO_FAST, r!!.status)
        assertEquals(TrackingQuality.LIMITED, r.sampleQuality)
    }

    @Test
    fun lostAfterTrackingMapsToLostAndReasons() {
        val gate = TrackingGate()
        still(gate, 30)
        assertEquals(TrackingStatus.LOST, gate.update(31 * frameNs, CameraTracking.PAUSED, FailureReason.NONE, Pose.IDENTITY, 0.5f).status)
        assertEquals(TrackingStatus.TOO_DARK, gate.update(32 * frameNs, CameraTracking.PAUSED, FailureReason.INSUFFICIENT_LIGHT, Pose.IDENTITY, 0.5f).status)
        assertEquals(TrackingStatus.LOW_TEXTURE, gate.update(33 * frameNs, CameraTracking.PAUSED, FailureReason.INSUFFICIENT_FEATURES, Pose.IDENTITY, 0.5f).status)
    }

    @Test
    fun lowLightWarnsButKeepsSamples() {
        val gate = TrackingGate()
        still(gate, 30)
        val r = gate.update(31 * frameNs, CameraTracking.TRACKING, FailureReason.NONE, Pose.IDENTITY, 0.02f)
        assertEquals(TrackingStatus.TOO_DARK, r.status)
        assertEquals(TrackingQuality.TRACKING, r.sampleQuality)
    }
}
