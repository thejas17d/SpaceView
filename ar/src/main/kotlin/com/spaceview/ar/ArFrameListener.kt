package com.spaceview.ar

import androidx.annotation.MainThread
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session

/**
 * What an AR feature needs from the host (S1).
 *
 * @property depth the Depth API is wanted (camera config with the depth sensor, depth mode on).
 * @property lights the host renders lit 3D content and must keep SceneView's main/fill light and indirect light
 *   (Place only; Measure and Scan draw lines in an overlay and skip the lights).
 * @property planeRenderer show SceneView's plane grid.
 */
data class ArRequirements(
    val depth: Boolean = false,
    val lights: Boolean = false,
    val planeRenderer: Boolean = true,
) {
    /** Union of two features' needs. */
    infix fun and(o: ArRequirements) =
        ArRequirements(depth || o.depth, lights || o.lights, planeRenderer || o.planeRenderer)
}

/**
 * One AR feature driven by the shared ARCore session (S1, ADR-001). The host ([ArFrameDispatcher] inside the
 * app's `ArSessionHost`) calls every listener **on the main thread, in list order**, once per SceneView callback.
 *
 * Rules:
 * - Only the **primary** listener (the first in the list, the one that owns accuracy, i.e. `MeasureSession` when
 *   present) chooses the camera config and configures the session; the others' [selectCameraConfig] and
 *   [configureSession] are never called.
 * - [onFrame] does native reads only and must not block: copy data out and hand heavy work to a worker.
 * - Anchors may be detached only in [onSessionPaused] (the M2 M5 rule) and never after it.
 * - New AR features are new listeners, never new sections of `MeasureSession` (ADR-001).
 */
interface ArFrameListener {
    /** This feature's needs; the host merges every listener's. */
    val requirements: ArRequirements get() = ArRequirements()

    /** Primary listener only: the camera config (SceneView's `sessionCameraConfig`). */
    @MainThread
    fun selectCameraConfig(session: Session): CameraConfig = SessionSetup.selectCameraConfig(session, requirements.depth)

    /** Primary listener only: SceneView's `sessionConfiguration`, after its typed defaults. */
    @MainThread
    fun configureSession(session: Session, config: Config) {
        SessionSetup.configure(config, depthActive = false)
    }

    @MainThread
    fun onSessionCreated(session: Session) {}

    @MainThread
    fun onSessionResumed(session: Session) {}

    /** The last moment anchors may be detached (SceneView then pauses and closes the session elsewhere). */
    @MainThread
    fun onSessionPaused(session: Session) {}

    /** Once per ARCore frame. */
    @MainThread
    fun onFrame(session: Session, frame: Frame)
}

/**
 * Fans SceneView's callbacks out to [listeners] in order (S1). The first listener is the primary one (see
 * [ArFrameListener]). Stateless apart from the list, so the host may rebuild it whenever the list changes.
 */
class ArFrameDispatcher(val listeners: List<ArFrameListener>) {
    init {
        require(listeners.isNotEmpty()) { "An AR host needs at least one listener" }
    }

    /** The listener that owns the camera config and the session configuration. */
    val primary: ArFrameListener get() = listeners[0]

    /** Every listener's needs combined. */
    val requirements: ArRequirements get() = listeners.drop(1).fold(listeners[0].requirements) { acc, l -> acc and l.requirements }

    fun selectCameraConfig(session: Session): CameraConfig = primary.selectCameraConfig(session)

    fun configureSession(session: Session, config: Config) = primary.configureSession(session, config)

    fun onSessionCreated(session: Session) { for (l in listeners) l.onSessionCreated(session) }

    fun onSessionResumed(session: Session) { for (l in listeners) l.onSessionResumed(session) }

    fun onSessionPaused(session: Session) { for (l in listeners) l.onSessionPaused(session) }

    fun onFrame(session: Session, frame: Frame) {
        // Indexed loop: no iterator allocation per frame.
        for (i in listeners.indices) listeners[i].onFrame(session, frame)
    }
}
