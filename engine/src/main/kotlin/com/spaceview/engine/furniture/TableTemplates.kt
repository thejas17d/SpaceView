package com.spaceview.engine.furniture

import com.spaceview.engine.PartMaterial
import com.spaceview.engine.furniture.FurnitureParams.BACK_T
import com.spaceview.engine.furniture.FurnitureParams.LEG_INSET
import com.spaceview.engine.furniture.FurnitureParams.LEG_SIZE
import com.spaceview.engine.furniture.FurnitureParams.PEDESTAL
import com.spaceview.engine.furniture.FurnitureParams.PEDESTAL_LEFT
import com.spaceview.engine.furniture.FurnitureParams.PEDESTAL_NONE
import com.spaceview.engine.furniture.FurnitureParams.PEDESTAL_RIGHT
import com.spaceview.engine.furniture.FurnitureParams.PEDESTAL_W
import com.spaceview.engine.furniture.FurnitureParams.SEAT
import com.spaceview.engine.furniture.FurnitureParams.SEAT_T
import com.spaceview.engine.furniture.FurnitureParams.TOP_T
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.D
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.H
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.W
import kotlin.math.min
import kotlin.math.roundToInt

/** Frozen numbers of `study_table_v1` (m). A new look is a new id with its own object, never an edit here. */
private object StudyTableV1 {
    const val LEG = 0.05
    const val FRONT_T = 0.018
    const val PANEL_T = 0.018
    const val GAP = 0.003
}

/**
 * Rectangular dining table (`dining_table_v1`, Pro): top, apron and four legs. Chairs are a separate item
 * ([DiningChairTemplate]), so each can be placed and checked on its own. Round tops are not offered (boxes only).
 *
 * Parameters: W [0.6, 3.0], D [0.6, 1.4], H [0.6, 0.9]; `topThickness` 0.015–0.06; `legSize` 0.03–0.12; `legInset`
 * (guided, DISTANCE: table edge to the leg) 0–0.30 within ≤ min(W, D)/2 − legSize − 0.1.
 */
object DiningTableTemplate : FurnitureTemplate(
    id = "dining_table_v1",
    params = listOf(
        ParamSpec(W, ParamKind.LENGTH, 0.6, 3.0, MeasureHint.DISTANCE, default = { 1.5 }),
        ParamSpec(D, ParamKind.LENGTH, 0.6, 1.4, MeasureHint.DISTANCE, default = { 0.9 }),
        ParamSpec(H, ParamKind.LENGTH, 0.6, 0.9, MeasureHint.HEIGHT, default = { 0.76 }),
        ParamSpec(TOP_T, ParamKind.LENGTH, 0.015, 0.06, default = { 0.03 }),
        ParamSpec(LEG_SIZE, ParamKind.LENGTH, 0.03, 0.12, default = { 0.06 }),
        ParamSpec(
            LEG_INSET, ParamKind.LENGTH, 0.0, 0.30, MeasureHint.DISTANCE, guided = true,
            default = { 0.05 },
            upper = { p -> min(p.getValue(W), p.getValue(D)) / 2 - p.getValue(LEG_SIZE) - 0.1 },
        ),
    ),
    presets = listOf(
        TemplatePreset("four_seat", mapOf(W to 1.2, D to 0.8)),
        TemplatePreset("six_seat", mapOf(W to 1.6, D to 0.9)),
    ),
) {
    override fun parts(p: Params, b: PartBuilder) {
        val top = p.getValue(TOP_T)
        val s = p.getValue(LEG_SIZE)
        val inset = p.getValue(LEG_INSET)
        val under = b.h - top
        val apron = min(0.08, 0.2 * under)
        b.box("top", PartMaterial.FRAME, b.left, b.right, under, b.h, b.back, b.front)
        b.box("apron", PartMaterial.BODY, b.left + inset, b.right - inset, under - apron, under, b.back + inset, b.front - inset)
        val x0 = b.left + inset
        val x1 = b.right - inset
        val z0 = b.back + inset
        val z1 = b.front - inset
        b.box("legBackLeft", PartMaterial.LEG, x0, x0 + s, 0.0, under, z0, z0 + s)
        b.box("legBackRight", PartMaterial.LEG, x1 - s, x1, 0.0, under, z0, z0 + s)
        b.box("legFrontLeft", PartMaterial.LEG, x0, x0 + s, 0.0, under, z1 - s, z1)
        b.box("legFrontRight", PartMaterial.LEG, x1 - s, x1, 0.0, under, z1 - s, z1)
    }
}

/**
 * Dining chair (`dining_chair_v1`, Pro): seat, four legs and a back panel; H is the top of the back.
 *
 * Parameters: W [0.35, 0.7], D [0.35, 0.75], H [0.7, 1.2]; `seatHeight` (guided, HEIGHT) 0.38–0.55 within ≤ H − 0.2;
 * `seatThickness` 0.02–0.10 within ≤ seat − 0.2; `legSize` 0.025–0.06; `backThickness` 0.02–0.08.
 */
object DiningChairTemplate : FurnitureTemplate(
    id = "dining_chair_v1",
    params = listOf(
        ParamSpec(W, ParamKind.LENGTH, 0.35, 0.7, MeasureHint.DISTANCE, default = { 0.45 }),
        ParamSpec(D, ParamKind.LENGTH, 0.35, 0.75, MeasureHint.DISTANCE, default = { 0.5 }),
        ParamSpec(H, ParamKind.LENGTH, 0.7, 1.2, MeasureHint.HEIGHT, default = { 0.9 }),
        ParamSpec(
            SEAT, ParamKind.LENGTH, 0.38, 0.55, MeasureHint.HEIGHT, guided = true,
            default = { 0.46 },
            upper = { p -> p.getValue(H) - 0.2 },
        ),
        ParamSpec(
            SEAT_T, ParamKind.LENGTH, 0.02, 0.10,
            default = { 0.05 },
            upper = { p -> p.getValue(SEAT) - 0.2 },
        ),
        ParamSpec(LEG_SIZE, ParamKind.LENGTH, 0.025, 0.06, default = { 0.04 }),
        ParamSpec(BACK_T, ParamKind.LENGTH, 0.02, 0.08, default = { 0.03 }),
    ),
) {
    override fun parts(p: Params, b: PartBuilder) {
        val seat = p.getValue(SEAT)
        val st = p.getValue(SEAT_T)
        val s = p.getValue(LEG_SIZE)
        val bt = p.getValue(BACK_T)
        b.box("seat", PartMaterial.SOFT, b.left, b.right, seat - st, seat, b.back, b.front)
        b.box("back", PartMaterial.FRAME, b.left, b.right, seat, b.h, b.back, b.back + bt)
        cornerLegs(b, seat - st, b.back, b.front, size = s)
    }
}

/**
 * Study table (`study_table_v1`, Pro): top, a drawer pedestal on the left, the right or none, legs, and a modesty
 * panel at the back.
 *
 * Parameters: W [0.6, 2.0], D [0.4, 0.9], H [0.6, 0.85]; `pedestal` 0 = none, 1 = left, 2 = right; `pedestalWidth`
 * (guided, DISTANCE; with a pedestal) 0.25–0.60 within ≤ W − 0.35; `topThickness` 0.015–0.05.
 */
object StudyTableTemplate : FurnitureTemplate(
    id = "study_table_v1",
    params = listOf(
        ParamSpec(W, ParamKind.LENGTH, 0.6, 2.0, MeasureHint.DISTANCE, default = { 1.2 }),
        ParamSpec(D, ParamKind.LENGTH, 0.4, 0.9, MeasureHint.DISTANCE, default = { 0.6 }),
        ParamSpec(H, ParamKind.LENGTH, 0.6, 0.85, MeasureHint.HEIGHT, default = { 0.75 }),
        ParamSpec(PEDESTAL, ParamKind.CHOICE, 0.0, 2.0, default = { PEDESTAL_RIGHT.toDouble() }),
        ParamSpec(
            PEDESTAL_W, ParamKind.LENGTH, 0.25, 0.60, MeasureHint.DISTANCE, guided = true,
            default = { 0.4 },
            upper = { p -> p.getValue(W) - 0.35 },
            appliesWhen = { p -> (p[PEDESTAL] ?: 0.0).roundToInt() != PEDESTAL_NONE },
        ),
        ParamSpec(TOP_T, ParamKind.LENGTH, 0.015, 0.05, default = { 0.025 }),
    ),
) {
    override fun parts(p: Params, b: PartBuilder) {
        val pedestal = p.getValue(PEDESTAL).roundToInt()
        val top = p.getValue(TOP_T)
        val under = b.h - top
        val s = StudyTableV1.LEG
        // Built with the pedestal on the right, mirrored for the left.
        b.mirrorX = pedestal == PEDESTAL_LEFT
        b.box("top", PartMaterial.FRAME, b.left, b.right, under, b.h, b.back, b.front)
        val panelZ0 = b.back + 0.02
        val panelY0 = under - 0.3
        if (pedestal == PEDESTAL_NONE) {
            cornerLegs(b, under, b.back, b.front, size = s)
            b.box("modesty", PartMaterial.BODY, b.left + s, b.right - s, panelY0, under, panelZ0, panelZ0 + StudyTableV1.PANEL_T)
            return
        }
        val pw = p.getValue(PEDESTAL_W)
        val split = b.right - pw
        b.box("legBack", PartMaterial.LEG, b.left, b.left + s, 0.0, under, b.back, b.back + s)
        b.box("legFront", PartMaterial.LEG, b.left, b.left + s, 0.0, under, b.front - s, b.front)
        b.box("pedestal", PartMaterial.BODY, split, b.right, 0.0, under, b.back, b.front - StudyTableV1.FRONT_T)
        val k = if (under >= 0.6) 3 else 2
        val y0 = 0.02
        val y1 = under - 0.005
        val pitch = (y1 - y0) / k
        for (i in 0 until k) {
            val a = y0 + i * pitch + if (i == 0) 0.0 else StudyTableV1.GAP / 2
            val c = y0 + (i + 1) * pitch - if (i == k - 1) 0.0 else StudyTableV1.GAP / 2
            b.box("drawer$i", PartMaterial.DOOR, split + 0.004, b.right - 0.004, a, c, b.front - StudyTableV1.FRONT_T, b.front)
        }
        b.box("modesty", PartMaterial.BODY, b.left + s, split, panelY0, under, panelZ0, panelZ0 + StudyTableV1.PANEL_T)
    }
}
