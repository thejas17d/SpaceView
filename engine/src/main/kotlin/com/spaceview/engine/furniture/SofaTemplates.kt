package com.spaceview.engine.furniture

import com.spaceview.engine.PartMaterial
import com.spaceview.engine.furniture.FurnitureParams.ARM_H
import com.spaceview.engine.furniture.FurnitureParams.ARM_W
import com.spaceview.engine.furniture.FurnitureParams.BACK_T
import com.spaceview.engine.furniture.FurnitureParams.LEG
import com.spaceview.engine.furniture.FurnitureParams.MAIN_D
import com.spaceview.engine.furniture.FurnitureParams.RETURN_W
import com.spaceview.engine.furniture.FurnitureParams.SEAT
import com.spaceview.engine.furniture.FurnitureParams.SEATS
import com.spaceview.engine.furniture.FurnitureParams.SIDE
import com.spaceview.engine.furniture.FurnitureParams.SIDE_LEFT
import com.spaceview.engine.furniture.FurnitureParams.SIDE_RIGHT
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.D
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.H
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.W
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Frozen numbers of `sofa_v1` (T-D0 review MINOR 4). They belong to this id only: a different look or range is a new
 * template id with its own object (`SofaV2`), never an edit here. `TemplateGoldenTest` fails on any change.
 */
private object SofaV1 {
    /** Narrowest seat cushion (m). */
    const val MIN_SEAT_W = 0.35

    /** Leg footprint (m). */
    const val LEG_SIZE = 0.05

    /** Gap between neighbouring cushions (m). */
    const val CUSHION_GAP = 0.006

    /** Seat-cushion thickness for a seat top [seat] above legs [leg] (at least 7.5 cm given the seat range). */
    fun cushion(seat: Double, leg: Double) = min(0.15, 0.5 * (seat - leg))
}

/** Frozen numbers of `lsofa_v1` (see [SofaV1]). */
private object LSofaV1 {
    const val LEG_SIZE = 0.05
    const val CUSHION_GAP = 0.006

    fun cushion(seat: Double, leg: Double) = min(0.15, 0.5 * (seat - leg))
}

/**
 * Straight sofa, 2 or 3 seats (`sofa_v1`, a Free core template). Back across the full width, an arm each side, a base
 * with one seat cushion and one back cushion per seat, and four legs when the leg height is above 0.
 *
 * Parameters: W [1.0, 4.0], D [0.6, 1.4], H (top of the back) [0.6, 1.2]; `seats` 2–3 (3 needs W ≥ 1.3 m); `legHeight`
 * 0–0.25 (≤ H − 0.45); `seatHeight` (guided, HEIGHT) 0.25–0.70 within [leg + 0.15, H − 0.15]; `armHeight` (guided,
 * HEIGHT) seat..H; `armWidth` (guided, DISTANCE) 0.05–0.35 with every seat ≥ 35 cm; `backThickness` 0.08–0.40
 * (≤ D − 0.35).
 */
object SofaTemplate : FurnitureTemplate(
    id = "sofa_v1",
    params = listOf(
        ParamSpec(W, ParamKind.LENGTH, 1.0, 4.0, MeasureHint.DISTANCE, default = { 1.8 }),
        ParamSpec(D, ParamKind.LENGTH, 0.6, 1.4, MeasureHint.DISTANCE, default = { 0.9 }),
        ParamSpec(H, ParamKind.LENGTH, 0.6, 1.2, MeasureHint.HEIGHT, default = { 0.85 }),
        ParamSpec(
            SEATS, ParamKind.COUNT, 2.0, 3.0,
            default = { p -> if (p.getValue(W) >= 1.9) 3.0 else 2.0 },
            upper = { p -> if (p.getValue(W) >= 1.3) 3.0 else 2.0 },
        ),
        ParamSpec(LEG, ParamKind.LENGTH, 0.0, 0.25, default = { 0.10 }, upper = { p -> p.getValue(H) - 0.45 }),
        ParamSpec(
            SEAT, ParamKind.LENGTH, 0.25, 0.70, MeasureHint.HEIGHT, guided = true,
            default = { p -> 0.52 * p.getValue(H) },
            lower = { p -> p.getValue(LEG) + 0.15 },
            upper = { p -> p.getValue(H) - 0.15 },
        ),
        ParamSpec(
            ARM_H, ParamKind.LENGTH, 0.25, 1.2, MeasureHint.HEIGHT, guided = true,
            default = { p -> p.getValue(SEAT) + 0.20 },
            lower = { p -> p.getValue(SEAT) },
            upper = { p -> p.getValue(H) },
        ),
        ParamSpec(
            ARM_W, ParamKind.LENGTH, 0.05, 0.35, MeasureHint.DISTANCE, guided = true,
            default = { 0.18 },
            upper = { p -> (p.getValue(W) - p.getValue(SEATS) * SofaV1.MIN_SEAT_W) / 2 },
        ),
        ParamSpec(BACK_T, ParamKind.LENGTH, 0.08, 0.40, default = { 0.20 }, upper = { p -> p.getValue(D) - 0.35 }),
    ),
    presets = listOf(
        TemplatePreset("two_seat", mapOf(SEATS to 2.0, W to 1.6)),
        TemplatePreset("three_seat", mapOf(SEATS to 3.0, W to 2.1)),
    ),
) {
    override fun parts(p: Params, b: PartBuilder) {
        val n = p.getValue(SEATS).roundToInt()
        val leg = p.getValue(LEG)
        val seat = p.getValue(SEAT)
        val armH = p.getValue(ARM_H)
        val aw = p.getValue(ARM_W)
        val bt = p.getValue(BACK_T)
        val ct = SofaV1.cushion(seat, leg)
        val gap = SofaV1.CUSHION_GAP
        val seatBack = b.back + bt
        b.box("back", PartMaterial.BODY, b.left, b.right, leg, b.h, b.back, seatBack)
        b.box("armLeft", PartMaterial.BODY, b.left, b.left + aw, leg, armH, seatBack, b.front)
        b.box("armRight", PartMaterial.BODY, b.right - aw, b.right, leg, armH, seatBack, b.front)
        b.box("base", PartMaterial.BODY, b.left + aw, b.right - aw, leg, seat - ct, seatBack, b.front)
        b.row("seat", PartMaterial.SOFT, n, b.left + aw, b.right - aw, gap, seat - ct, seat, seatBack, b.front)
        val bcd = min(0.15, 0.35 * (b.d - bt))
        b.row("backCushion", PartMaterial.SOFT, n, b.left + aw, b.right - aw, gap, seat, seat + 0.8 * (b.h - seat), seatBack, seatBack + bcd)
        if (leg > 0) cornerLegs(b, leg, b.back, b.front, SofaV1.LEG_SIZE)
    }
}

/**
 * L-shaped sofa with the return (chaise) on the viewer's left or right (`lsofa_v1`, Pro). W and D are the overall
 * footprint: D runs from the back to the front of the return. The verdict compares the overall box (an L is
 * conservative there; the card says "overall box").
 *
 * Parameters: W [1.8, 4.5], D [1.2, 3.0], H [0.6, 1.2]; `side` 0 = return on the left, 1 = on the right; `legHeight`
 * 0–0.25 (≤ H − 0.45); `seatHeight` (guided) 0.25–0.70 within [leg + 0.15, H − 0.15]; `armHeight` seat..H; `armWidth`
 * 0.05–0.30; `mainDepth` (guided, DISTANCE: depth of the straight part) 0.6–1.4 within ≤ D − 0.3; `returnWidth`
 * (guided, DISTANCE: width of the return) 0.6–1.4 within [arm + 0.35, W − 0.9]; `backThickness` 0.08–0.40
 * (≤ mainDepth − 0.35).
 */
object LSofaTemplate : FurnitureTemplate(
    id = "lsofa_v1",
    params = listOf(
        ParamSpec(W, ParamKind.LENGTH, 1.8, 4.5, MeasureHint.DISTANCE, default = { 2.6 }),
        ParamSpec(D, ParamKind.LENGTH, 1.2, 3.0, MeasureHint.DISTANCE, default = { 1.6 }),
        ParamSpec(H, ParamKind.LENGTH, 0.6, 1.2, MeasureHint.HEIGHT, default = { 0.85 }),
        ParamSpec(SIDE, ParamKind.CHOICE, 0.0, 1.0, default = { SIDE_RIGHT.toDouble() }),
        ParamSpec(LEG, ParamKind.LENGTH, 0.0, 0.25, default = { 0.10 }, upper = { p -> p.getValue(H) - 0.45 }),
        ParamSpec(
            SEAT, ParamKind.LENGTH, 0.25, 0.70, MeasureHint.HEIGHT, guided = true,
            default = { p -> 0.52 * p.getValue(H) },
            lower = { p -> p.getValue(LEG) + 0.15 },
            upper = { p -> p.getValue(H) - 0.15 },
        ),
        ParamSpec(
            ARM_H, ParamKind.LENGTH, 0.25, 1.2, MeasureHint.HEIGHT,
            default = { p -> p.getValue(SEAT) + 0.20 },
            lower = { p -> p.getValue(SEAT) },
            upper = { p -> p.getValue(H) },
        ),
        ParamSpec(ARM_W, ParamKind.LENGTH, 0.05, 0.30, default = { 0.18 }),
        ParamSpec(
            MAIN_D, ParamKind.LENGTH, 0.6, 1.4, MeasureHint.DISTANCE, guided = true,
            default = { 0.9 },
            upper = { p -> p.getValue(D) - 0.3 },
        ),
        ParamSpec(
            RETURN_W, ParamKind.LENGTH, 0.6, 1.4, MeasureHint.DISTANCE, guided = true,
            default = { 0.9 },
            lower = { p -> p.getValue(ARM_W) + 0.35 },
            upper = { p -> p.getValue(W) - 0.9 },
        ),
        ParamSpec(BACK_T, ParamKind.LENGTH, 0.08, 0.40, default = { 0.20 }, upper = { p -> p.getValue(MAIN_D) - 0.35 }),
    ),
    presets = listOf(
        TemplatePreset("left", mapOf(SIDE to SIDE_LEFT.toDouble())),
        TemplatePreset("right", mapOf(SIDE to SIDE_RIGHT.toDouble())),
    ),
) {
    override fun parts(p: Params, b: PartBuilder) {
        // Built with the return on the right, mirrored for the left.
        b.mirrorX = p.getValue(SIDE).roundToInt() == SIDE_LEFT
        val leg = p.getValue(LEG)
        val seat = p.getValue(SEAT)
        val armH = p.getValue(ARM_H)
        val aw = p.getValue(ARM_W)
        val bt = p.getValue(BACK_T)
        val md = p.getValue(MAIN_D)
        val rw = p.getValue(RETURN_W)
        val ct = LSofaV1.cushion(seat, leg)
        val gap = LSofaV1.CUSHION_GAP
        val seatBack = b.back + bt
        val mainFront = b.back + md
        val split = b.right - rw // main part | return
        b.box("back", PartMaterial.BODY, b.left, b.right, leg, b.h, b.back, seatBack)
        b.box("armMain", PartMaterial.BODY, b.left, b.left + aw, leg, armH, seatBack, mainFront)
        b.box("armReturn", PartMaterial.BODY, b.right - aw, b.right, leg, armH, seatBack, b.front)
        b.box("baseMain", PartMaterial.BODY, b.left + aw, split, leg, seat - ct, seatBack, mainFront)
        b.box("baseReturn", PartMaterial.BODY, split, b.right - aw, leg, seat - ct, seatBack, b.front)
        val n = ((split - b.left - aw) / 0.65).roundToInt().coerceIn(1, 3)
        b.row("seatMain", PartMaterial.SOFT, n, b.left + aw, split, gap, seat - ct, seat, seatBack, mainFront)
        b.box("seatReturn", PartMaterial.SOFT, split + gap / 2, b.right - aw, seat - ct, seat, seatBack, b.front)
        val bcd = min(0.15, 0.35 * (md - bt))
        val backTop = seat + 0.8 * (b.h - seat)
        b.row("backCushion", PartMaterial.SOFT, n, b.left + aw, split, gap, seat, backTop, seatBack, seatBack + bcd)
        b.box("backCushionReturn", PartMaterial.SOFT, split + gap / 2, b.right - aw, seat, backTop, seatBack, seatBack + bcd)
        if (leg > 0) {
            val s = LSofaV1.LEG_SIZE
            b.box("legBackLeft", PartMaterial.LEG, b.left, b.left + s, 0.0, leg, b.back, b.back + s)
            b.box("legBackRight", PartMaterial.LEG, b.right - s, b.right, 0.0, leg, b.back, b.back + s)
            b.box("legMainFront", PartMaterial.LEG, b.left, b.left + s, 0.0, leg, mainFront - s, mainFront)
            b.box("legReturnOuter", PartMaterial.LEG, b.right - s, b.right, 0.0, leg, b.front - s, b.front)
            b.box("legReturnInner", PartMaterial.LEG, split, split + s, 0.0, leg, b.front - s, b.front)
        }
    }
}
