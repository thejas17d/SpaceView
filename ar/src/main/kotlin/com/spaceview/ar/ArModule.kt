package com.spaceview.ar

/**
 * Entry point of the `:ar` module: ARCore session lifecycle, tracking-quality gating, hit tests,
 * anchors and the frame → `:engine` sample pipeline (see docs/PLAN.md, section 3). No UI lives here.
 *
 * Placeholder for M0. Nothing in this module may run at process start: the ARCore session is
 * created lazily, off the main thread where the API allows, only when the user opens an AR screen.
 */
object ArModule {
    /** SpaceView v1 ships as "AR Required" (manifest `com.google.ar.core` = `required`). */
    const val AR_REQUIRED: Boolean = true
}
