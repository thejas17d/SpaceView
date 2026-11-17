package com.spaceview.app.furniture

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.spaceview.engine.Palette
import com.spaceview.engine.Part
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * A small oblique line drawing of [parts] (front, top and right faces), coloured with [palette]. Drawn from the
 * template's own geometry, so it costs no assets and always matches the model (ADR-010: APK size stays flat).
 */
@Composable
fun FurnitureSketch(parts: List<Part>, palette: Palette, description: String, modifier: Modifier = Modifier) {
    val faces = remember(parts, palette) { SketchFaces.of(parts, palette) }
    Canvas(modifier.semantics { contentDescription = description }) {
        if (faces.isEmpty()) return@Canvas
        val b = SketchFaces.bounds(faces)
        val scale = 0.9f * min(size.width / max(b[2] - b[0], 1e-3f), size.height / max(b[3] - b[1], 1e-3f))
        val ox = size.width / 2 - scale * (b[0] + b[2]) / 2
        val oy = size.height / 2 + scale * (b[1] + b[3]) / 2
        val stroke = Stroke(width = 1f * density)
        for (f in faces) {
            val path = Path()
            f.points.forEachIndexed { i, (u, v) ->
                val p = Offset(ox + scale * u, oy - scale * v)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path, f.fill)
            drawPath(path, f.edge, style = stroke)
        }
    }
}

/** The projected faces of a sketch, back to front (pure; the Canvas only scales them). */
internal object SketchFaces {
    /** Cabinet oblique projection: depth recedes up and to the right at 30°, at half scale. */
    private const val K_U = 0.43f
    private const val K_V = 0.25f

    class Face(val points: List<Pair<Float, Float>>, val fill: Color, val edge: Color)

    private fun project(x: Double, y: Double, z: Double): Pair<Float, Float> =
        Pair((x - K_U * z).toFloat(), (y - K_V * z).toFloat())

    fun of(parts: List<Part>, palette: Palette): List<Face> {
        // Painter's order: far (small z front face) first, then low, then left.
        val ordered = parts.sortedWith(
            compareBy<Part>({ it.center.z + it.size.z / 2 }, { it.center.y }, { it.center.x }),
        )
        val out = ArrayList<Face>(ordered.size * 3)
        for (p in ordered) {
            val x0 = p.center.x - p.size.x / 2; val x1 = p.center.x + p.size.x / 2
            val y0 = p.center.y - p.size.y / 2; val y1 = p.center.y + p.size.y / 2
            val z0 = p.center.z - p.size.z / 2; val z1 = p.center.z + p.size.z / 2
            val base = Color(0xFF000000.toInt() or palette.colorOf(p.material))
            val edge = lerp(base, Color.Black, 0.45f)
            out += Face(listOf(project(x0, y1, z1), project(x1, y1, z1), project(x1, y1, z0), project(x0, y1, z0)), lerp(base, Color.White, 0.25f), edge)
            out += Face(listOf(project(x1, y0, z1), project(x1, y0, z0), project(x1, y1, z0), project(x1, y1, z1)), lerp(base, Color.Black, 0.2f), edge)
            out += Face(listOf(project(x0, y0, z1), project(x1, y0, z1), project(x1, y1, z1), project(x0, y1, z1)), base, edge)
        }
        return out
    }

    /** [minU, minV, maxU, maxV] of all faces. */
    fun bounds(faces: List<Face>): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (f in faces) for ((u, v) in f.points) {
            b[0] = min(b[0], u); b[1] = min(b[1], v); b[2] = max(b[2], u); b[3] = max(b[3], v)
        }
        return b
    }
}

/**
 * The generated model in a non-AR SceneView that the user can turn (drag) and zoom (pinch). The model is normalised to
 * a 1-unit cube and centred, so every template frames the same way; its true size is in the numbers beside it.
 */
@Composable
fun FurniturePreview3D(glb: ByteArray, description: String, modifier: Modifier = Modifier) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val manipulator = rememberCameraManipulator(orbitRadius = 2.6f, targetPosition = Float3(0f, 0f, 0f))
    SceneView(
        modifier = modifier.semantics { contentDescription = description },
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = manipulator,
    ) {
        val instance = remember(glb) {
            runCatching {
                val buffer = ByteBuffer.allocateDirect(glb.size).put(glb).also { it.flip() }
                modelLoader.createModelInstance(buffer)
            }.getOrNull()
        }
        if (instance != null) {
            ModelNode(modelInstance = instance, scaleToUnits = 1.0f, centerOrigin = Float3(0f, 0f, 0f))
        }
    }
}
