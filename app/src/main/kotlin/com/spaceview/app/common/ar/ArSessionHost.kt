package com.spaceview.app.common.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.google.ar.core.Config
import com.spaceview.ar.ArFrameDispatcher
import com.spaceview.ar.ArFrameListener
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.ar.ARSceneView

/**
 * The single AR host (S1, ADR-001): SceneView's ARCore session fanned out to [listeners], which are called on
 * the main thread **in list order**. The first listener is the primary one: it alone selects the camera config
 * and configures the session (MeasureSession wherever it is present).
 *
 * Lights follow the listeners' merged [com.spaceview.ar.ArRequirements.lights]: Measure and Scan draw their lines
 * in a 2-D overlay and skip SceneView's lights; Place renders a lit model and keeps SceneView's default main and
 * fill lights and its AR environment (indirect light), see ADR-003.
 *
 * The listener list must stay the same for the life of the host (key the caller on anything that would change
 * it); [planeRenderer] may change at any time and is AND-ed with the listeners' wish.
 *
 * @param content 3-D nodes (Place's model). The per-frame transform of a node is set by its listener, never
 *   through Compose state (§3.5).
 */
@Composable
fun ArSessionHost(
    listeners: List<ArFrameListener>,
    planeRenderer: Boolean,
    onSessionFailed: () -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable ARSceneScope.() -> Unit)? = null,
) {
    val dispatcher = remember(listeners) { ArFrameDispatcher(listeners) }
    val req = dispatcher.requirements
    val showPlanes = planeRenderer && req.planeRenderer
    if (req.lights) {
        // SceneView's default main + fill lights and AR environment stay on (lit model on Place).
        ARSceneView(
            modifier = modifier.fillMaxSize(),
            sessionCameraConfig = dispatcher::selectCameraConfig,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
            depthMode = Config.DepthMode.DISABLED,
            instantPlacementMode = Config.InstantPlacementMode.DISABLED,
            sessionConfiguration = dispatcher::configureSession,
            planeRenderer = showPlanes,
            onSessionCreated = dispatcher::onSessionCreated,
            onSessionResumed = dispatcher::onSessionResumed,
            onSessionPaused = dispatcher::onSessionPaused,
            onSessionFailure = { onSessionFailed() },
            onSessionUpdated = dispatcher::onFrame,
            onGestureListener = null,
            // Permission and Google Play Services for AR were handled by ArGate.
            permissionHandler = null,
            cameraPermissionOverlay = null,
            arCoreAvailabilityOverlay = null,
            onARCoreAvailability = { if (it != null) onSessionFailed() },
            content = content,
        )
    } else {
        ARSceneView(
            modifier = modifier.fillMaxSize(),
            sessionCameraConfig = dispatcher::selectCameraConfig,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
            depthMode = Config.DepthMode.DISABLED,
            instantPlacementMode = Config.InstantPlacementMode.DISABLED,
            sessionConfiguration = dispatcher::configureSession,
            planeRenderer = showPlanes,
            // Nothing lit is rendered (lines are drawn by the overlay): skip the lights.
            mainLightNode = null,
            fillLightNode = null,
            onSessionCreated = dispatcher::onSessionCreated,
            onSessionResumed = dispatcher::onSessionResumed,
            onSessionPaused = dispatcher::onSessionPaused,
            onSessionFailure = { onSessionFailed() },
            onSessionUpdated = dispatcher::onFrame,
            onGestureListener = null,
            permissionHandler = null,
            cameraPermissionOverlay = null,
            arCoreAvailabilityOverlay = null,
            onARCoreAvailability = { if (it != null) onSessionFailed() },
            content = content,
        )
    }
}
