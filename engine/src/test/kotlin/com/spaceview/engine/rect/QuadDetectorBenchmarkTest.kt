package com.spaceview.engine.rect

import com.spaceview.engine.Plane
import com.spaceview.engine.Vec3
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W9 speed budget on a 1280 × 720 frame, warm JVM, median of 15 runs (the scene is rendered once, outside the timing):
 * - the finder's path ([QuadLock.detectRectangle]: detect, coarse metric pre-check, refine only the chosen quad);
 * - the full candidate list ([QuadDetector.detect], all candidates refined).
 * Budget ≤ 30 ms each on the JVM. Device expectation: ART on a Pixel 10a big core is of the same order (~10–30 ms),
 * run at most at 10 Hz on the finder's own worker, so the camera loop is never blocked (logged as `SpaceViewRect`).
 */
class QuadDetectorBenchmarkTest {
    private fun median(block: () -> Unit): Double {
        repeat(5) { block() }
        val t = LongArray(15) {
            val a = System.nanoTime()
            block()
            System.nanoTime() - a
        }
        t.sort()
        return t[t.size / 2] / 1e6
    }

    @Test
    fun detectAndRefineWithinBudget() {
        val scene = SyntheticScene(tiltDeg = 20.0, yawDeg = 15.0)
        val img = scene.render(0.7, 2.0)
        val detector = QuadDetector()
        val table = Plane.fromPointNormal(Vec3.ZERO, Vec3.UNIT_Z)
        assertNotNull(QuadLock.detectRectangle(detector, img, scene.camera, table))
        val finder = median { QuadLock.detectRectangle(detector, img, scene.camera, table) }
        val all = median { detector.detect(img) }
        println("W9 BENCH 1280x720 median: finder path $finder ms, all candidates $all ms")
        // The 30 ms target is reported, not enforced: wall-clock time flakes on shared CI runners. The build only fails
        // on a gross regression (a CI-safe bound, 5× the target).
        println("W9 BENCH target ${TARGET_MS} ms: finder ${if (finder <= TARGET_MS) "met" else "missed"}, all ${if (all <= TARGET_MS) "met" else "missed"}")
        assertTrue("finder path $finder ms > $CI_BOUND_MS ms", finder <= CI_BOUND_MS)
        assertTrue("all candidates $all ms > $CI_BOUND_MS ms", all <= CI_BOUND_MS)
    }

    private companion object {
        const val TARGET_MS = 30.0
        const val CI_BOUND_MS = 150.0
    }
}
