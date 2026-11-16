package com.spaceview.app.place

import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.Fit
import com.spaceview.engine.FitPolicy
import com.spaceview.engine.FitRotation
import com.spaceview.engine.FitVerdict
import com.spaceview.engine.Measurement
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.Uncertainty
import kotlin.math.ceil
import kotlin.math.floor

/** What the user measured as the target space in Place. */
enum class SpaceKind {
    /** A gap (DISTANCE): only the item's width is compared. */
    GAP,

    /** A rectangle (RECTANGLE): W × H on a wall, W × D on a floor. */
    AREA,
}

/**
 * A space measurement taken on the Place screen, with its own ± (σ). For [SpaceKind.AREA], [primaryM] is the
 * horizontal side when [primaryHorizontal] (as measured from the rectangle's first edge), else the vertical one.
 * Stored in `SavedStateHandle` as plain numbers (a length survives a pause and process death, §3.6).
 */
data class SpaceReading(
    val kind: SpaceKind,
    val primaryM: Double,
    val primarySigma: Double,
    val secondaryM: Double? = null,
    val secondarySigma: Double? = null,
    val primaryHorizontal: Boolean = true,
)

/** The item axis that limits the fit. */
enum class FitAxis { WIDTH, HEIGHT, DEPTH }

/**
 * The verdict card's content.
 *
 * @property clearanceM space − item on the limiting axis (m); negative when too big.
 * @property bandM the combined 95% band on that axis (linear sum, ADR-002).
 * @property usedTypedSize a compared item side was typed or preset (σ = 0): "using your typed size".
 * @property widthOnly a gap was measured: only the width was compared.
 * @property heightNotChecked the height was not compared (a gap, or a floor rectangle: W × D only; M4 review MINOR 3).
 * @property rotated the item fits only turned (portrait ↔ landscape, or W ↔ D on the floor).
 * @property boundingBox the item is a template whose footprint is not a box (an L-sofa): the overall box is used.
 */
data class PlaceFit(
    val fit: Fit,
    val limitingAxis: FitAxis,
    val clearanceM: Double,
    val bandM: Double,
    val usedTypedSize: Boolean,
    val widthOnly: Boolean,
    val heightNotChecked: Boolean,
    val rotated: Boolean,
    val boundingBox: Boolean,
)

/**
 * The Fits / Tight / Too big verdict for Place (§3.4 step 5). Pure; JVM-tested.
 *
 * - Item σ: bound95 / 1.96 for MEASURED sides (the stored bound95 is rounded **up**, so this is conservative);
 *   typed and preset sides are exact (σ = 0) and the card says so.
 * - Uncertainties combine linearly ([FitPolicy] default, decision D6). FITS and TOO_BIG are only shown outside the
 *   combined band; everything else is TIGHT.
 * - Rotation: a frame may be turned portrait ↔ landscape (ANY); a TV and other wall items never (NONE); a floor
 *   item may turn about the vertical, i.e. W ↔ D on a floor rectangle. A gap compares the width as placed.
 */
object PlaceVerdict {

    fun evaluate(
        kind: ObjectKind,
        width: Dimension,
        height: Dimension,
        depth: Dimension,
        onWall: Boolean,
        space: SpaceReading,
    ): PlaceFit? {
        if (!space.primaryM.isFinite() || space.primaryM <= 0 || !space.primarySigma.isFinite() || space.primarySigma < 0) return null
        val spaceA = Measurement(space.primaryM, space.primarySigma)
        val (itemDims, spaceDims, axes, rotation) = when (space.kind) {
            SpaceKind.GAP -> Quad(listOf(width), listOf(spaceA), listOf(FitAxis.WIDTH), FitRotation.NONE)
            SpaceKind.AREA -> {
                val sm = space.secondaryM ?: return null
                val ss = space.secondarySigma ?: return null
                if (!sm.isFinite() || sm <= 0 || !ss.isFinite() || ss < 0) return null
                val spaceB = Measurement(sm, ss)
                // Space as (horizontal, other): the rectangle's first edge may have been the vertical one.
                val ordered = if (space.primaryHorizontal) listOf(spaceA, spaceB) else listOf(spaceB, spaceA)
                if (onWall) {
                    Quad(listOf(width, height), ordered, listOf(FitAxis.WIDTH, FitAxis.HEIGHT), if (kind == ObjectKind.FRAME) FitRotation.ANY else FitRotation.NONE)
                } else {
                    // On a floor both rectangle sides are horizontal: turning the item swaps W and D.
                    Quad(listOf(width, depth), ordered, listOf(FitAxis.WIDTH, FitAxis.DEPTH), FitRotation.ANY)
                }
            }
        }
        val result = FitVerdict.evaluate(itemDims.map(::sigmaOf), spaceDims, FitPolicy(rotation = rotation))
        val limiting = result.limitingAxis
        return PlaceFit(
            fit = result.fit,
            limitingAxis = axes[limiting.itemAxis],
            clearanceM = limiting.clearance.value,
            bandM = limiting.clearance.bound95,
            usedTypedSize = itemDims.any { it.isEstimate },
            widthOnly = space.kind == SpaceKind.GAP,
            heightNotChecked = space.kind == SpaceKind.GAP || !onWall,
            rotated = result.rotated,
            boundingBox = kind == ObjectKind.FURNITURE,
        )
    }

    /** Item side as a measurement: measured → σ = bound95 / 1.96; typed or preset → exact. */
    fun sigmaOf(d: Dimension): Measurement = Measurement(d.meters, if (d.isEstimate) 0.0 else d.bound95 / Uncertainty.K95)

    /**
     * A whole-unit amount for the card, rounded in the cautious direction: spare is rounded **down**, an overshoot
     * **up** ("Fits, 6 cm spare", "Too big by 4 cm"). Imperial uses whole inches; anything else whole centimetres.
     */
    fun amount(meters: Double, pref: UnitPreference, roundUp: Boolean): Amount {
        val m = kotlin.math.abs(meters)
        return if (pref == UnitPreference.IMPERIAL) {
            val inches = m / 0.0254
            Amount((if (roundUp) ceil(inches - 1e-9) else floor(inches + 1e-9)).toLong(), imperial = true)
        } else {
            val cm = m * 100
            Amount((if (roundUp) ceil(cm - 1e-9) else floor(cm + 1e-9)).toLong(), imperial = false)
        }
    }

    /** A whole number of centimetres or inches. */
    data class Amount(val value: Long, val imperial: Boolean)

    private data class Quad(val item: List<Dimension>, val space: List<Measurement>, val axes: List<FitAxis>, val rotation: FitRotation)
}
