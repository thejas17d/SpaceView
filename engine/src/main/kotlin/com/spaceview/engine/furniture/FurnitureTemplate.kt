package com.spaceview.engine.furniture

import com.spaceview.engine.Part
import com.spaceview.engine.PartMaterial
import com.spaceview.engine.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A template's parameters by id, in metres (lengths) or whole numbers (counts and choices). */
typealias Params = Map<String, Double>

/** What a parameter is. */
enum class ParamKind {
    /** A length in metres. */
    LENGTH,

    /** A whole number of things (seats, doors, shelves). */
    COUNT,

    /** One of a few numbered options (side: 0 = left, 1 = right…); the template documents the numbers. */
    CHOICE,
}

/** How the guided flow obtains a length (ARCHITECTURE-v2 §6.1). */
enum class MeasureHint {
    /** A point-to-point distance in AR (Measure's DISTANCE mode). */
    DISTANCE,

    /** A vertical distance in AR (Measure's HEIGHT mode). */
    HEIGHT,

    /** Typed only (too small or hidden to measure well in AR), or not a length. */
    TYPED_ONLY,
}

/**
 * One template parameter. Its valid interval may depend on parameters listed **before** it ([lower], [upper]); the
 * static [min] / [max] always bound it. [default] also sees only earlier parameters, so a template's defaults follow
 * the measured W, D and H.
 *
 * @property guided measured as a key extra in the guided flow (after W, D, H); others keep their default unless typed.
 * @property appliesWhen false when the parameter means nothing for these earlier values (a headboard's thickness
 *   without a headboard): it is then left out of the resolved map and never validated.
 */
class ParamSpec(
    val id: String,
    val kind: ParamKind,
    val min: Double,
    val max: Double,
    val hint: MeasureHint = MeasureHint.TYPED_ONLY,
    val guided: Boolean = false,
    val default: (Params) -> Double,
    val lower: (Params) -> Double = { min },
    val upper: (Params) -> Double = { max },
    val appliesWhen: (Params) -> Boolean = { true },
) {
    init {
        require(ID.matches(id)) { "Bad parameter id '$id'" }
        require(min.isFinite() && max.isFinite() && min <= max) { "Bad static range for $id" }
        require(kind == ParamKind.LENGTH || hint == MeasureHint.TYPED_ONLY) { "Only lengths are measured" }
    }

    /** The valid interval given the earlier parameters in [p] (never wider than [min]..[max]). */
    fun range(p: Params): ClosedFloatingPointRange<Double> {
        var lo = max(min, lower(p))
        var hi = min(max, upper(p))
        if (kind != ParamKind.LENGTH) {
            lo = kotlin.math.ceil(lo - EPS)
            hi = floor(hi + EPS)
        }
        return lo..hi
    }

    /** Whole-number options of a [ParamKind.COUNT] / [ParamKind.CHOICE] parameter (empty for a length). */
    fun options(p: Params): List<Int> {
        if (kind == ParamKind.LENGTH) return emptyList()
        val r = range(p)
        return (r.start.roundToInt()..r.endInclusive.roundToInt()).toList()
    }

    /** The default for these earlier values, inside the valid interval (rounded for whole numbers). */
    fun defaultFor(p: Params): Double {
        val r = range(p)
        val d = default(p)
        val v = if (r.start > r.endInclusive) r.start else d.coerceIn(r.start, r.endInclusive)
        return if (kind == ParamKind.LENGTH) v else v.roundToInt().toDouble().coerceIn(r.start, max(r.start, r.endInclusive))
    }

    companion object {
        private val ID = Regex("[A-Za-z][A-Za-z0-9_]*")
        internal const val EPS = 1e-9
    }
}

/**
 * Parameter ids of the templates (stored as JSON keys in `template_params`: never rename one of a shipped template).
 * W, D and H are [FurnitureTemplate.W], [FurnitureTemplate.D] and [FurnitureTemplate.H].
 */
object FurnitureParams {
    const val SEATS = "seats"
    const val SIDE = "side"
    const val LEG = "legHeight"
    const val SEAT = "seatHeight"
    const val ARM_H = "armHeight"
    const val ARM_W = "armWidth"
    const val BACK_T = "backThickness"
    const val MAIN_D = "mainDepth"
    const val RETURN_W = "returnWidth"
    const val HEADBOARD = "headboard"
    const val BED_HEIGHT = "bedHeight"
    const val MATTRESS = "mattress"
    const val HEADBOARD_T = "headboardThickness"
    const val DOORS = "doors"
    const val PLINTH = "plinthHeight"
    const val TOP_T = "topThickness"
    const val LEG_SIZE = "legSize"
    const val LEG_INSET = "legInset"
    const val SEAT_T = "seatThickness"
    const val COMPARTMENTS = "compartments"
    const val SHELVES = "shelves"
    const val PEDESTAL = "pedestal"
    const val PEDESTAL_W = "pedestalWidth"

    /** [SIDE] values (L-sofa return): on the viewer's left or right. */
    const val SIDE_LEFT = 0
    const val SIDE_RIGHT = 1

    /** [PEDESTAL] values (study table drawers): none, on the viewer's left, on the right. */
    const val PEDESTAL_NONE = 0
    const val PEDESTAL_LEFT = 1
    const val PEDESTAL_RIGHT = 2
}

/** Why a parameter value is not accepted. [limit] is the bound it broke (metres or a count), when there is one. */
data class ParamError(val paramId: String, val reason: Reason, val limit: Double? = null) {
    enum class Reason { MISSING, NOT_FINITE, NOT_WHOLE, TOO_SMALL, TOO_LARGE }
}

/** A named starting point (a 3-seat sofa, a queen bed): values that prefill the flow, never stored by name. */
data class TemplatePreset(val id: String, val values: Params)

/**
 * A parametric furniture template (ADR-010). Pure and deterministic: the same parameters always give the same parts.
 *
 * **Invariant:** the union bounding box of [build]'s parts is exactly W × H × D (to rounding of 1e-12 m; the gallery
 * checks 0.1 mm). Every part is axis-aligned (yaw 0) in the model frame: X ∈ [−W/2, W/2] (+X = viewer's right),
 * Y ∈ [0, H] (+Y up), Z ∈ [−D/2, D/2] (+Z = the front, towards the viewer).
 *
 * **Never change the geometry or the ranges of a shipped id.** A stored item is rebuilt from its id and parameters when
 * its model is stale (`TemplateModelSource`), so a different look or range needs a **new id** (`sofa_v2`, a new object
 * with its own constants) while `sofa_v1` stays exactly as it is. Each template keeps its numbers in its own private
 * `…V1` object; no number is shared between ids. The shared primitives ([PartBuilder.box], [PartBuilder.row],
 * `cornerLegs`) are frozen infrastructure: changing them changes every shipped model. `TemplateGoldenTest` pins a hash
 * of the parts of every shipped id and fails on any change.
 */
abstract class FurnitureTemplate(
    /** Stored by name in Room (`template_id`): never rename. */
    val id: String,
    /** W, D, H first, then options, then the other lengths; each may depend only on those before it. */
    val params: List<ParamSpec>,
    val presets: List<TemplatePreset> = emptyList(),
) {
    init {
        require(params.size >= 3 && params[0].id == W && params[1].id == D && params[2].id == H) { "$id: W, D, H come first" }
        require(params.map { it.id }.toSet().size == params.size) { "$id: duplicate parameter" }
        require(params.none { it.id == FurniturePalettes.KEY }) { "$id: '${FurniturePalettes.KEY}' is reserved" }
    }

    fun spec(paramId: String): ParamSpec? = params.firstOrNull { it.id == paramId }

    /** The extras measured in the guided flow after W, D and H, in order (at most 3). */
    val guidedExtras: List<ParamSpec> get() = params.drop(3).filter { it.guided }

    /** The options (counts and choices) the flow asks for before measuring. */
    val options: List<ParamSpec> get() = params.filter { it.kind != ParamKind.LENGTH }

    /**
     * The full parameter map for [given]: given values are kept as they are (validation reports bad ones), missing
     * ones get their default, parameters that do not apply are dropped, and unknown keys (e.g. the palette) are
     * ignored. Ordered as [params].
     */
    fun resolve(given: Params): Params {
        val out = LinkedHashMap<String, Double>()
        for (spec in params) {
            if (!spec.appliesWhen(out)) continue
            out[spec.id] = given[spec.id] ?: spec.defaultFor(out)
        }
        return out
    }

    /** Problems with [p] (a resolved map), in parameter order; empty when [build] may be called. */
    fun validate(p: Params): List<ParamError> {
        val errors = ArrayList<ParamError>()
        val seen = LinkedHashMap<String, Double>()
        for (spec in params) {
            if (!spec.appliesWhen(seen)) continue
            val v = p[spec.id]
            when {
                v == null -> errors += ParamError(spec.id, ParamError.Reason.MISSING)
                !v.isFinite() -> errors += ParamError(spec.id, ParamError.Reason.NOT_FINITE)
                spec.kind != ParamKind.LENGTH && abs(v - v.roundToInt()) > ParamSpec.EPS ->
                    errors += ParamError(spec.id, ParamError.Reason.NOT_WHOLE)
                else -> {
                    val r = spec.range(seen)
                    if (v < r.start - ParamSpec.EPS) errors += ParamError(spec.id, ParamError.Reason.TOO_SMALL, r.start)
                    else if (v > r.endInclusive + ParamSpec.EPS) errors += ParamError(spec.id, ParamError.Reason.TOO_LARGE, r.endInclusive)
                }
            }
            // A later range is computed from what was given, valid or not (its own error, if any, is reported too).
            seen[spec.id] = v?.takeIf { it.isFinite() } ?: spec.defaultFor(seen)
        }
        return errors
    }

    /**
     * The parts for valid parameters [p] (see [validate]; unknown keys are ignored). Throws
     * [IllegalArgumentException] for invalid parameters. The result's union box is checked to be W × H × D.
     */
    fun build(p: Params): List<Part> {
        val resolved = resolve(p)
        val errors = validate(resolved)
        require(errors.isEmpty()) { "$id: invalid parameters $errors" }
        val parts = PartBuilder(resolved.getValue(W), resolved.getValue(H), resolved.getValue(D)).also { parts(resolved, it) }.parts
        val box = unionBox(parts)
        check(
            abs(box.x - resolved.getValue(W)) < UNION_EPS && abs(box.y - resolved.getValue(H)) < UNION_EPS &&
                abs(box.z - resolved.getValue(D)) < UNION_EPS,
        ) { "$id: parts span $box, not W x H x D" }
        return parts
    }

    /** Adds the parts for resolved, valid [p] to [b]. */
    protected abstract fun parts(p: Params, b: PartBuilder)

    override fun toString(): String = id

    companion object {
        /** Overall width (X), metres. */
        const val W = "W"

        /** Overall depth (Z), front to back, metres. */
        const val D = "D"

        /** Overall height (Y), floor to the highest point, metres. */
        const val H = "H"

        /** [build] checks the union box to this (m); far inside the gallery's 0.1 mm. */
        const val UNION_EPS = 1e-9

        /** Union bounding box (extents) of axis-aligned [parts]. */
        fun unionBox(parts: List<Part>): Vec3 {
            require(parts.isNotEmpty())
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var z0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var z1 = -Double.MAX_VALUE
            for (p in parts) {
                require(p.yawRad == 0.0) { "Template parts are axis-aligned" }
                x0 = min(x0, p.center.x - p.size.x / 2); x1 = max(x1, p.center.x + p.size.x / 2)
                y0 = min(y0, p.center.y - p.size.y / 2); y1 = max(y1, p.center.y + p.size.y / 2)
                z0 = min(z0, p.center.z - p.size.z / 2); z1 = max(z1, p.center.z + p.size.z / 2)
            }
            return Vec3(x1 - x0, y1 - y0, z1 - z0)
        }
    }
}

/**
 * Four square legs of side [size] at the corners of the footprint from [z0] (back) to [z1] (front), touching W's outer
 * faces. The size is always the calling template's own constant (nothing shared between template ids).
 */
internal fun cornerLegs(b: PartBuilder, height: Double, z0: Double, z1: Double, size: Double) {
    b.box("legBackLeft", PartMaterial.LEG, b.left, b.left + size, 0.0, height, z0, z0 + size)
    b.box("legBackRight", PartMaterial.LEG, b.right - size, b.right, 0.0, height, z0, z0 + size)
    b.box("legFrontLeft", PartMaterial.LEG, b.left, b.left + size, 0.0, height, z1 - size, z1)
    b.box("legFrontRight", PartMaterial.LEG, b.right - size, b.right, 0.0, height, z1 - size, z1)
}

/**
 * Collects axis-aligned parts given by their faces, in the model frame of a W × H × D item. [mirrorX] reflects every
 * part about X = 0 (an L-sofa's return or a desk's pedestal on the other side).
 */
class PartBuilder internal constructor(val w: Double, val h: Double, val d: Double) {
    val parts = ArrayList<Part>()
    var mirrorX = false

    /** Left / right / back / front faces of the whole item. */
    val left get() = -w / 2
    val right get() = w / 2
    val back get() = -d / 2
    val front get() = d / 2

    /** A part from its faces: [x0] < [x1] (left → right), [y0] < [y1] (bottom → top), [z0] < [z1] (back → front). */
    fun box(name: String, material: PartMaterial, x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double) {
        check(x1 > x0 && y1 > y0 && z1 > z0) { "Part '$name' is empty: x $x0..$x1, y $y0..$y1, z $z0..$z1" }
        val cx = (x0 + x1) / 2
        parts += Part(
            name = name,
            center = Vec3(if (mirrorX) -cx else cx, (y0 + y1) / 2, (z0 + z1) / 2),
            size = Vec3(x1 - x0, y1 - y0, z1 - z0),
            yawRad = 0.0,
            material = material,
        )
    }

    /**
     * [n] equal parts side by side across [x0]..[x1] with [gap] between neighbours (none at the outer edges), from
     * left to right; [name] gets the index.
     */
    fun row(name: String, material: PartMaterial, n: Int, x0: Double, x1: Double, gap: Double, y0: Double, y1: Double, z0: Double, z1: Double) {
        val pitch = (x1 - x0) / n
        for (i in 0 until n) {
            val a = if (i == 0) x0 else x0 + i * pitch + gap / 2
            val c = if (i == n - 1) x1 else x0 + (i + 1) * pitch - gap / 2
            box("$name$i", material, a, c, y0, y1, z0, z1)
        }
    }
}
