package com.spaceview.engine.furniture

import com.spaceview.engine.PartMaterial
import com.spaceview.engine.furniture.FurnitureParams.COMPARTMENTS
import com.spaceview.engine.furniture.FurnitureParams.DOORS
import com.spaceview.engine.furniture.FurnitureParams.LEG
import com.spaceview.engine.furniture.FurnitureParams.PLINTH
import com.spaceview.engine.furniture.FurnitureParams.SHELVES
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.D
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.H
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.W
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Frozen numbers of `wardrobe_v1` (m). A new look is a new id with its own object, never an edit here. */
private object WardrobeV1 {
    const val DOOR_T = 0.02
    const val HANDLE_T = 0.02
    const val GAP = 0.003
}

/** Frozen numbers of `tv_unit_v1` (m). */
private object TvUnitV1 {
    const val TOP_T = 0.025
    const val FRONT_T = 0.018
    const val GAP = 0.003
}

/** Frozen numbers of `bookshelf_v1` (m). */
private object BookshelfV1 {
    /** Board thickness. */
    const val T = 0.018
    const val BACK_T = 0.006

    /** Narrowest opening between two boards. */
    const val MIN_OPENING = 0.18

    /** Height between the bottom board and the top board. */
    fun shelfSpace(p: Params) = p.getValue(H) - p.getValue(PLINTH) - 2 * T
}

/**
 * Hinged wardrobe, 2 or 3 doors (`wardrobe_v1`, a Free core template): carcass, a recessed plinth, full-height doors and
 * one bar handle per door (the handles are the front-most 2 cm, inside D).
 *
 * Parameters: W [0.6, 3.0], D [0.4, 0.8], H [1.2, 2.6]; `doors` 2–3 (3 needs W ≥ 0.9); `plinthHeight` (guided,
 * HEIGHT) 0–0.20.
 */
object WardrobeTemplate : FurnitureTemplate(
    id = "wardrobe_v1",
    params = listOf(
        ParamSpec(W, ParamKind.LENGTH, 0.6, 3.0, MeasureHint.DISTANCE, default = { 1.2 }),
        ParamSpec(D, ParamKind.LENGTH, 0.4, 0.8, MeasureHint.DISTANCE, default = { 0.6 }),
        ParamSpec(H, ParamKind.LENGTH, 1.2, 2.6, MeasureHint.HEIGHT, default = { 2.1 }),
        ParamSpec(
            DOORS, ParamKind.COUNT, 2.0, 3.0,
            default = { p -> if (p.getValue(W) >= 1.35) 3.0 else 2.0 },
            upper = { p -> if (p.getValue(W) >= 0.9) 3.0 else 2.0 },
        ),
        ParamSpec(PLINTH, ParamKind.LENGTH, 0.0, 0.20, MeasureHint.HEIGHT, guided = true, default = { 0.08 }),
    ),
    presets = listOf(
        TemplatePreset("two_door", mapOf(DOORS to 2.0, W to 1.0)),
        TemplatePreset("three_door", mapOf(DOORS to 3.0, W to 1.5)),
    ),
) {
    override fun parts(p: Params, b: PartBuilder) {
        val n = p.getValue(DOORS).roundToInt()
        val plinth = p.getValue(PLINTH)
        val doorBack = b.front - WardrobeV1.HANDLE_T - WardrobeV1.DOOR_T
        val doorFront = b.front - WardrobeV1.HANDLE_T
        b.box("carcass", PartMaterial.BODY, b.left, b.right, plinth, b.h, b.back, doorBack)
        if (plinth > 0) b.box("plinth", PartMaterial.LEG, b.left + 0.02, b.right - 0.02, 0.0, plinth, b.back + 0.02, doorBack - 0.03)
        b.row("door", PartMaterial.DOOR, n, b.left, b.right, WardrobeV1.GAP, plinth, b.h, doorBack, doorFront)
        // One vertical bar per door, on the edge nearest the middle (the middle door of three: its left edge).
        val pitch = b.w / n
        val hh = min(0.4, 0.3 * (b.h - plinth))
        val yc = (plinth + b.h) / 2
        for (i in 0 until n) {
            val x0 = b.left + i * pitch
            val x1 = x0 + pitch
            val onRight = (x0 + x1) / 2 < -1e-9
            val hx = if (onRight) x1 - 0.07 else x0 + 0.05
            b.box("handle$i", PartMaterial.ACCENT, hx, hx + 0.02, yc - hh / 2, yc + hh / 2, doorFront, b.front)
        }
    }
}

/**
 * Low TV unit (`tv_unit_v1`, Pro): top board, carcass, 2–4 equal fronts, optional legs.
 *
 * Parameters: W [0.8, 3.0], D [0.25, 0.6], H [0.3, 0.9]; `compartments` 2–4 (each ≥ 35 cm); `legHeight` (guided,
 * HEIGHT) 0–0.20 within ≤ H − 0.2.
 */
object TvUnitTemplate : FurnitureTemplate(
    id = "tv_unit_v1",
    params = listOf(
        ParamSpec(W, ParamKind.LENGTH, 0.8, 3.0, MeasureHint.DISTANCE, default = { 1.8 }),
        ParamSpec(D, ParamKind.LENGTH, 0.25, 0.6, MeasureHint.DISTANCE, default = { 0.4 }),
        ParamSpec(H, ParamKind.LENGTH, 0.3, 0.9, MeasureHint.HEIGHT, default = { 0.5 }),
        ParamSpec(
            COMPARTMENTS, ParamKind.COUNT, 2.0, 4.0,
            default = { p -> (p.getValue(W) / 0.6).roundToInt().toDouble() },
            upper = { p -> floor(p.getValue(W) / 0.35) },
        ),
        ParamSpec(
            LEG, ParamKind.LENGTH, 0.0, 0.20, MeasureHint.HEIGHT, guided = true,
            default = { 0.08 },
            upper = { p -> p.getValue(H) - 0.2 },
        ),
    ),
) {
    override fun parts(p: Params, b: PartBuilder) {
        val n = p.getValue(COMPARTMENTS).roundToInt()
        val leg = p.getValue(LEG)
        val topY = b.h - TvUnitV1.TOP_T
        val frontBack = b.front - TvUnitV1.FRONT_T
        b.box("top", PartMaterial.FRAME, b.left, b.right, topY, b.h, b.back, b.front)
        b.box("body", PartMaterial.BODY, b.left, b.right, leg, topY, b.back, frontBack)
        b.row("front", PartMaterial.DOOR, n, b.left, b.right, TvUnitV1.GAP, leg, topY - TvUnitV1.GAP, frontBack, b.front)
        if (leg > 0) {
            val s = 0.04
            val i = 0.03
            b.box("legBackLeft", PartMaterial.LEG, b.left + i, b.left + i + s, 0.0, leg, b.back + i, b.back + i + s)
            b.box("legBackRight", PartMaterial.LEG, b.right - i - s, b.right - i, 0.0, leg, b.back + i, b.back + i + s)
            b.box("legFrontLeft", PartMaterial.LEG, b.left + i, b.left + i + s, 0.0, leg, frontBack - i - s, frontBack - i)
            b.box("legFrontRight", PartMaterial.LEG, b.right - i - s, b.right - i, 0.0, leg, frontBack - i - s, frontBack - i)
        }
    }
}

/**
 * Open bookshelf (`bookshelf_v1`, Pro): two full-height sides, top, bottom board on an optional plinth, a thin back and
 * evenly spaced shelves.
 *
 * Parameters: W [0.4, 2.4], D [0.2, 0.5], H [0.6, 2.4]; `plinthHeight` (guided, HEIGHT) 0–0.15; `shelves` 1–10 with at
 * least 18 cm per opening.
 */
object BookshelfTemplate : FurnitureTemplate(
    id = "bookshelf_v1",
    params = listOf(
        ParamSpec(W, ParamKind.LENGTH, 0.4, 2.4, MeasureHint.DISTANCE, default = { 0.8 }),
        ParamSpec(D, ParamKind.LENGTH, 0.2, 0.5, MeasureHint.DISTANCE, default = { 0.3 }),
        ParamSpec(H, ParamKind.LENGTH, 0.6, 2.4, MeasureHint.HEIGHT, default = { 1.8 }),
        ParamSpec(PLINTH, ParamKind.LENGTH, 0.0, 0.15, MeasureHint.HEIGHT, guided = true, default = { 0.06 }),
        ParamSpec(
            SHELVES, ParamKind.COUNT, 1.0, 10.0,
            default = { p -> ((p.getValue(H) - p.getValue(PLINTH)) / 0.35).roundToInt() - 1.0 },
            upper = { p -> floor(BookshelfV1.shelfSpace(p) / BookshelfV1.MIN_OPENING) },
        ),
    ),
) {
    override fun parts(p: Params, b: PartBuilder) {
        val t = BookshelfV1.T
        val plinth = p.getValue(PLINTH)
        val k = p.getValue(SHELVES).roundToInt()
        b.box("sideLeft", PartMaterial.BODY, b.left, b.left + t, 0.0, b.h, b.back, b.front)
        b.box("sideRight", PartMaterial.BODY, b.right - t, b.right, 0.0, b.h, b.back, b.front)
        b.box("top", PartMaterial.BODY, b.left + t, b.right - t, b.h - t, b.h, b.back, b.front)
        b.box("bottom", PartMaterial.BODY, b.left + t, b.right - t, plinth, plinth + t, b.back, b.front)
        if (plinth > 0) b.box("plinth", PartMaterial.LEG, b.left + t, b.right - t, 0.0, plinth, b.back + 0.01, b.front - 0.02)
        val a = plinth + t
        val top = b.h - t
        b.box("back", PartMaterial.BODY, b.left + t, b.right - t, a, top, b.back, b.back + BookshelfV1.BACK_T)
        val pitch = (top - a) / (k + 1)
        for (j in 1..k) {
            val y = a + j * pitch
            b.box("shelf$j", PartMaterial.FRAME, b.left + t, b.right - t, y - t / 2, y + t / 2, b.back + BookshelfV1.BACK_T, b.front - 0.01)
        }
    }
}
