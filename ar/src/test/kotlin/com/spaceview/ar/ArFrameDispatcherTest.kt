package com.spaceview.ar

import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** S1 / ADR-001: listeners are called in order; only the primary configures the session. */
class ArFrameDispatcherTest {
    // ARCore's protected test constructors: no native session behind them.
    private class TestSession : Session()
    private class TestFrame : Frame()
    private class TestConfig : Config()
    private class TestCameraConfig : CameraConfig()

    private class Recorder(val name: String, val log: MutableList<String>, override val requirements: ArRequirements) : ArFrameListener {
        val camera = TestCameraConfig()
        override fun selectCameraConfig(session: Session): CameraConfig { log += "$name.camera"; return camera }
        override fun configureSession(session: Session, config: Config) { log += "$name.configure" }
        override fun onSessionCreated(session: Session) { log += "$name.created" }
        override fun onSessionResumed(session: Session) { log += "$name.resumed" }
        override fun onSessionPaused(session: Session) { log += "$name.paused" }
        override fun onFrame(session: Session, frame: Frame) { log += "$name.frame" }
    }

    @Test fun dispatchesInOrderAndOnlyThePrimaryConfigures() {
        val log = ArrayList<String>()
        val a = Recorder("measure", log, ArRequirements(depth = true))
        val b = Recorder("place", log, ArRequirements(lights = true, planeRenderer = false))
        val d = ArFrameDispatcher(listOf(a, b))
        val s = TestSession()
        assertSame(a.camera, d.selectCameraConfig(s))
        d.configureSession(s, TestConfig())
        d.onSessionCreated(s)
        d.onSessionResumed(s)
        d.onFrame(s, TestFrame())
        d.onFrame(s, TestFrame())
        d.onSessionPaused(s)
        assertEquals(
            listOf(
                "measure.camera", "measure.configure",
                "measure.created", "place.created",
                "measure.resumed", "place.resumed",
                "measure.frame", "place.frame", "measure.frame", "place.frame",
                "measure.paused", "place.paused",
            ),
            log,
        )
        assertSame(a, d.primary)
    }

    @Test fun requirementsAreMerged() {
        val log = ArrayList<String>()
        val d = ArFrameDispatcher(
            listOf(
                Recorder("a", log, ArRequirements(depth = false, lights = false, planeRenderer = false)),
                Recorder("b", log, ArRequirements(depth = true, lights = true, planeRenderer = false)),
            ),
        )
        assertEquals(ArRequirements(depth = true, lights = true, planeRenderer = false), d.requirements)
        assertEquals(ArRequirements(), ArRequirements() and ArRequirements())
    }

    @Test(expected = IllegalArgumentException::class)
    fun needsAListener() { ArFrameDispatcher(emptyList()) }
}
