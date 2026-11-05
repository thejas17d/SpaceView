package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.Vec3

/**
 * The front-photo capture state machine, without ARCore (JVM-tested). [MeasureSession] feeds it once per
 * `onSessionUpdated` call on the main thread and takes the photo when [onFrame] says so.
 *
 * - Armed with a finished [ScanOutline]: the front face it decided ([ScanOutline.choice]) is the one
 *   photographed, never re-decided (M3 review M1).
 * - Repeated camera frames (same timestamp; SceneView calls back at display rate) are skipped (B1).
 * - Points without anchors (left by a pause) are never photographed against: the capture disarms with
 *   [CaptureHint.REMEASURE] (M2).
 * - Every arm / disarm / capture bumps [generation], so a photo converted in the background is only
 *   published if nothing changed meanwhile (m2).
 */
class FrontCaptureController(
    private val gateFactory: (ScanShape) -> FrontCaptureGate = { FrontCaptureGate(maxAngleDeg = FrontCaptureGate.maxAngleFor(it)) },
) {
    /** Guidance for the screen; null when not armed (and no outcome to show). */
    var status: FrontCaptureStatus? = null
        private set

    /** Bumped on every arm, disarm and capture. */
    var generation = 0
        private set

    private var outline: ScanOutline? = null
    private var gate: FrontCaptureGate? = null
    private var face: FrontFace? = null
    private var frames = 0
    private var lastTs = Long.MIN_VALUE

    val isArmed: Boolean get() = outline != null

    /** The outline being captured (while armed). */
    val armedOutline: ScanOutline? get() = outline

    /**
     * Arms the capture for [outline]. Refused (status [CaptureHint.REMEASURE]) when [pointsAnchored] is
     * false: the outline's points are no longer anchored to the world.
     */
    fun arm(outline: ScanOutline, pointsAnchored: Boolean): Boolean {
        generation++
        face = null
        frames = 0
        lastTs = Long.MIN_VALUE
        if (!pointsAnchored) {
            this.outline = null
            status = FrontCaptureStatus(CaptureHint.REMEASURE)
            return false
        }
        this.outline = outline
        gate = gateFactory(outline.shape).also { it.reset() }
        status = FrontCaptureStatus(CaptureHint.NOT_TRACKING)
        return true
    }

    /** Disarms and clears the status (Back, Undo, pause, new session). */
    fun disarm() {
        generation++
        outline = null
        face = null
        status = null
    }

    /**
     * One `onSessionUpdated` call. [points] gives the current (anchor-refined) endpoint positions; it is
     * only called when the face is rebuilt (every [FACE_REFRESH_FRAMES] new camera frames). Writes the
     * projected corners into [cornersOut]. True when the photo should be taken from this frame.
     */
    fun onFrame(
        timestampNs: Long,
        trackingOk: Boolean,
        pointsAnchored: Boolean,
        sensorPose: Pose,
        points: () -> List<Vec3>,
        camera: CpuImageCamera,
        cornersOut: FloatArray,
    ): Boolean {
        val o = outline ?: return false
        val g = gate ?: return false
        if (timestampNs == lastTs) return false // the same camera image again (display-rate callback)
        lastTs = timestampNs
        if (!pointsAnchored) {
            generation++
            outline = null
            face = null
            status = FrontCaptureStatus(CaptureHint.REMEASURE)
            return false
        }
        if (face == null || ++frames % FACE_REFRESH_FRAMES == 0) face = ScanComposer.face(o.shape, points(), o.choice)
        val f = face
        if (f == null) {
            status = FrontCaptureStatus(CaptureHint.FACE_OBJECT)
            return false
        }
        val fit = ImageProjection.fitCorners(f.corners, sensorPose, camera, cornersOut)
        val decision = g.update(timestampNs, trackingOk, sensorPose, f.normal, fit)
        status = decision.status
        return decision.capture
    }

    /** The face to photograph now, from the current [points] (null when not armed or degenerate). */
    fun faceNow(points: List<Vec3>): FrontFace? = outline?.let { ScanComposer.face(it.shape, points, it.choice) }

    /** The photo was taken: disarm with [CaptureHint.CAPTURED]. Returns the generation to publish under. */
    fun onCaptured(): Int {
        generation++
        outline = null
        face = null
        status = FrontCaptureStatus(CaptureHint.CAPTURED)
        return generation
    }

    /** Taking the photo failed this frame (no image, corners moved out): keep armed, restart the hold. */
    fun onCaptureFailed() {
        gate?.reset()
        lastTs = Long.MIN_VALUE
    }

    companion object {
        /** While armed, the face is rebuilt from the anchors this often (new camera frames). */
        const val FACE_REFRESH_FRAMES = 10
    }
}
