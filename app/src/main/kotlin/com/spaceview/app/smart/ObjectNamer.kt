package com.spaceview.app.smart

import androidx.annotation.StringRes
import com.spaceview.app.R
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.data.gallery.ObjectKind.APPLIANCE
import com.spaceview.data.gallery.ObjectKind.BOX
import com.spaceview.data.gallery.ObjectKind.FRAME
import com.spaceview.data.gallery.ObjectKind.FURNITURE
import com.spaceview.data.gallery.ObjectKind.OTHER
import com.spaceview.data.gallery.ObjectKind.TV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A name the user may accept for a scanned object (SM1b, contract K6). Only ever one of the curated names
 * ([LabelMapper]): shown through [nameRes] in the app's language (EN or HI), never as the model's raw label (D11).
 *
 * @property id stable curated id ("sofa"), for tests and logs.
 * @property confidence the model's confidence for the label it came from (0..1).
 */
data class NameSuggestion(val id: String, @StringRes val nameRes: Int, val confidence: Float)

/** Contract K6: a name suggestion for the photo of a scanned object, or null. Never throws. */
interface ObjectNamer {
    /**
     * Suggests a name for [jpeg] (the straightened front photo). [kind] (the type the user picked) filters out names
     * that cannot be that kind of object. Null when the model is unavailable or not yet downloaded, on any error or
     * timeout, or when nothing curated is confident enough.
     */
    suspend fun suggest(jpeg: ByteArray, kind: ObjectKind? = null): NameSuggestion?
}

/** One label from an image labeller: the model's English label text and its confidence. */
data class RawLabel(val text: String, val confidence: Float)

/**
 * The labeller seam: ML Kit on the device ([MlKitImageLabelSource]), a fake in JVM tests. May throw (model
 * missing, decode failure); [LabelObjectNamer] turns every failure into "no suggestion".
 */
interface ImageLabelSource {
    suspend fun labels(jpeg: ByteArray): List<RawLabel>
}

/**
 * [ObjectNamer] over an [ImageLabelSource]: runs it on [dispatcher] (never the main thread), at most [timeoutMs], and
 * maps the labels through [LabelMapper].
 *
 * Failures never throw, but they are not silent either (SM1 review MINOR 12): each is reported to [onFailure] with a
 * reason (a class name or "timeout", never user data), so a naming chip that disappears after an ML Kit bump shows up
 * in the log. Only the **caller's** cancellation propagates; a cancellation from inside the labeller (ML Kit cancelling
 * its own task) is a failure like any other.
 */
class LabelObjectNamer(
    private val source: ImageLabelSource,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val minConfidence: Float = LabelMapper.MIN_CONFIDENCE,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onFailure: (reason: String) -> Unit = {},
) : ObjectNamer {
    override suspend fun suggest(jpeg: ByteArray, kind: ObjectKind?): NameSuggestion? {
        if (jpeg.isEmpty()) return null
        val labels = try {
            withTimeoutOrNull(timeoutMs) { withContext(dispatcher) { source.labels(jpeg) } }
                ?: return null.also { onFailure("timeout") }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the caller was cancelled: propagate
            onFailure("cancelled: ${e.javaClass.simpleName}")
            return null
        } catch (e: Exception) {
            onFailure(e.javaClass.name)
            return null
        }
        return LabelMapper.map(labels, kind, minConfidence)
    }

    companion object {
        /** Labelling one ≤ 1024 px photo takes well under a second on a mid-range phone; never wait longer than this. */
        const val TIMEOUT_MS = 3_000L
    }
}

/**
 * The curated allowlist (decision D11): ML Kit base-model labels (its published label map, English) → our own name
 * in EN + HI string resources, plus the object types the name fits. Only objects people measure, scan and place at
 * home; scene, activity and person labels ("Room", "Sitting", "Smile") are deliberately absent. Several labels may
 * share one name (Shoe / Sneakers).
 */
object LabelMapper {
    /** A label must be at least this confident to be offered. Tunable on the device. */
    const val MIN_CONFIDENCE = 0.70f

    /** One curated name. [kinds] are the scan types it fits ([ObjectKind.OTHER] accepts every name). */
    class Curated(val id: String, @StringRes val nameRes: Int, val kinds: Set<ObjectKind>)

    private val FLAT = setOf(FRAME, OTHER)
    private val SCREEN = setOf(TV, APPLIANCE, OTHER, BOX)
    private val FURN = setOf(FURNITURE, BOX, OTHER)
    private val ITEM = setOf(BOX, OTHER)

    private fun c(id: String, @StringRes res: Int, kinds: Set<ObjectKind>) = Curated(id, res, kinds)

    /** ML Kit label text → curated name. */
    val table: Map<String, Curated> = run {
        val poster = c("poster", R.string.smart_name_poster, FLAT)
        val painting = c("painting", R.string.smart_name_painting, FLAT)
        val whiteboard = c("whiteboard", R.string.smart_name_whiteboard, FLAT)
        val blackboard = c("blackboard", R.string.smart_name_blackboard, FLAT)
        val clock = c("clock", R.string.smart_name_clock, setOf(FRAME, APPLIANCE, BOX, OTHER))
        val tv = c("tv", R.string.smart_name_tv, SCREEN)
        val computer = c("computer", R.string.smart_name_computer, SCREEN)
        val phone = c("phone", R.string.smart_name_phone, setOf(APPLIANCE, BOX, OTHER))
        val speaker = c("speaker", R.string.smart_name_speaker, setOf(APPLIANCE, BOX, OTHER))
        val piano = c("piano", R.string.smart_name_piano, FURN)
        val instrument = c("instrument", R.string.smart_name_instrument, ITEM)
        val chair = c("chair", R.string.smart_name_chair, FURN)
        val sofa = c("sofa", R.string.smart_name_sofa, FURN)
        val loveseat = c("loveseat", R.string.smart_name_loveseat, FURN)
        val desk = c("desk", R.string.smart_name_desk, FURN)
        val cabinet = c("cabinet", R.string.smart_name_cabinet, FURN)
        val drawers = c("drawers", R.string.smart_name_drawers, FURN)
        val shelf = c("shelf", R.string.smart_name_shelf, FURN)
        val bunkBed = c("bunk_bed", R.string.smart_name_bunk_bed, FURN)
        val bench = c("bench", R.string.smart_name_bench, FURN)
        val pillow = c("pillow", R.string.smart_name_pillow, ITEM)
        val cushion = c("cushion", R.string.smart_name_cushion, ITEM)
        val curtain = c("curtain", R.string.smart_name_curtain, FLAT)
        val lamp = c("lamp", R.string.smart_name_lamp, setOf(APPLIANCE, BOX, OTHER))
        val flowerpot = c("flowerpot", R.string.smart_name_flowerpot, ITEM)
        val plant = c("plant", R.string.smart_name_plant, ITEM)
        val sink = c("sink", R.string.smart_name_sink, setOf(APPLIANCE, FURNITURE, BOX, OTHER))
        val bag = c("bag", R.string.smart_name_bag, ITEM)
        val handbag = c("handbag", R.string.smart_name_handbag, ITEM)
        val toy = c("toy", R.string.smart_name_toy, ITEM)
        val softToy = c("soft_toy", R.string.smart_name_soft_toy, ITEM)
        val blocks = c("blocks", R.string.smart_name_blocks, ITEM)
        val statue = c("statue", R.string.smart_name_statue, ITEM)
        val aquarium = c("aquarium", R.string.smart_name_aquarium, setOf(APPLIANCE, BOX, OTHER))
        val bicycle = c("bicycle", R.string.smart_name_bicycle, setOf(OTHER))
        val shoes = c("shoes", R.string.smart_name_shoes, ITEM)
        val cup = c("cup", R.string.smart_name_cup, ITEM)
        val cookware = c("cookware", R.string.smart_name_cookware, ITEM)
        val umbrella = c("umbrella", R.string.smart_name_umbrella, ITEM)
        val helmet = c("helmet", R.string.smart_name_helmet, ITEM)
        val tyre = c("tyre", R.string.smart_name_tyre, ITEM)
        val skateboard = c("skateboard", R.string.smart_name_skateboard, ITEM)
        mapOf(
            "Poster" to poster, "Watercolor paint" to painting, "Whiteboard" to whiteboard, "Blackboard" to blackboard,
            "Clock" to clock, "Television" to tv, "Computer" to computer, "Mobile phone" to phone, "Subwoofer" to speaker,
            "Piano" to piano, "Musical instrument" to instrument, "Chair" to chair, "Couch" to sofa,
            "Loveseat" to loveseat, "Desk" to desk, "Cabinetry" to cabinet, "Drawer" to drawers, "Shelf" to shelf,
            "Bunk bed" to bunkBed, "Bench" to bench, "Pillow" to pillow, "Cushion" to cushion, "Curtain" to curtain,
            "Lampshade" to lamp, "Flowerpot" to flowerpot, "Plant" to plant, "Sink" to sink, "Bag" to bag,
            "Handbag" to handbag, "Toy" to toy, "Stuffed toy" to softToy, "Plush" to softToy, "Lego" to blocks,
            "Statue" to statue, "Aquarium" to aquarium, "Bicycle" to bicycle, "Shoe" to shoes, "Sneakers" to shoes,
            "Cup" to cup, "Cookware and bakeware" to cookware, "Umbrella" to umbrella, "Helmet" to helmet,
            "Tire" to tyre, "Skateboard" to skateboard,
        )
    }

    /**
     * The most confident curated label that fits [kind] (null = any) with at least [minConfidence], or null. Labels
     * outside the table are never shown, in any language.
     */
    fun map(labels: List<RawLabel>, kind: ObjectKind?, minConfidence: Float = MIN_CONFIDENCE): NameSuggestion? {
        var best: RawLabel? = null
        var bestCurated: Curated? = null
        for (l in labels) {
            if (!(l.confidence >= minConfidence) || l.confidence > 1f) continue // also drops NaN
            val curated = table[l.text.trim()] ?: continue
            if (kind != null && kind != OTHER && kind !in curated.kinds) continue
            if (best == null || l.confidence > best.confidence) {
                best = l
                bestCurated = curated
            }
        }
        val b = best ?: return null
        val cur = bestCurated ?: return null
        return NameSuggestion(cur.id, cur.nameRes, b.confidence)
    }
}
