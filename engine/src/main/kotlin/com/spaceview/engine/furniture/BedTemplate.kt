package com.spaceview.engine.furniture

import com.spaceview.engine.PartMaterial
import com.spaceview.engine.furniture.FurnitureParams.BED_HEIGHT
import com.spaceview.engine.furniture.FurnitureParams.HEADBOARD
import com.spaceview.engine.furniture.FurnitureParams.HEADBOARD_T
import com.spaceview.engine.furniture.FurnitureParams.LEG
import com.spaceview.engine.furniture.FurnitureParams.MATTRESS
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.D
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.H
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.W
import kotlin.math.min
import kotlin.math.roundToInt

/** Rules shared by the bed's parameter specs and geometry. */
internal object BedRules {
    fun hasHeadboard(p: Params) = (p[HEADBOARD] ?: 0.0).roundToInt() == 1

    /** Top of the mattress: the measured bed height with a headboard, else the overall height. */
    fun top(p: Params) = if (hasHeadboard(p)) p.getValue(BED_HEIGHT) else p.getValue(H)
}

/**
 * Bed with an optional headboard (`bed_v1`, a Free core template). D is the length (head to foot); the head is at the
 * back (−Z). H is the overall height: the top of the headboard, or the top of the mattress without one.
 *
 * Parameters: W [0.8, 2.2], D [1.8, 2.4], H [0.25, 1.6]; `headboard` 0/1 (1 needs H ≥ 0.5); `bedHeight` (guided,
 * HEIGHT; top of the mattress, headboard only) 0.25–1.0 within ≤ H − 0.15; `mattress` (guided, HEIGHT: thickness)
 * 0.08–0.35 within ≤ top − 0.05; `legHeight` 0–0.30 within ≤ top − mattress − 0.05; `headboardThickness` 0.03–0.15
 * (headboard only). Presets single / double / queen / king prefill typical Indian frame sizes (measure yours).
 */
object BedTemplate : FurnitureTemplate(
    id = "bed_v1",
    params = listOf(
        ParamSpec(W, ParamKind.LENGTH, 0.8, 2.2, MeasureHint.DISTANCE, default = { 1.6 }),
        ParamSpec(D, ParamKind.LENGTH, 1.8, 2.4, MeasureHint.DISTANCE, default = { 2.0 }),
        ParamSpec(H, ParamKind.LENGTH, 0.25, 1.6, MeasureHint.HEIGHT, default = { 1.0 }),
        ParamSpec(
            HEADBOARD, ParamKind.CHOICE, 0.0, 1.0,
            default = { p -> if (p.getValue(H) >= 0.6) 1.0 else 0.0 },
            upper = { p -> if (p.getValue(H) >= 0.5) 1.0 else 0.0 },
        ),
        ParamSpec(
            BED_HEIGHT, ParamKind.LENGTH, 0.25, 1.0, MeasureHint.HEIGHT, guided = true,
            default = { 0.5 },
            upper = { p -> p.getValue(H) - 0.15 },
            appliesWhen = BedRules::hasHeadboard,
        ),
        ParamSpec(
            MATTRESS, ParamKind.LENGTH, 0.08, 0.35, MeasureHint.HEIGHT, guided = true,
            default = { 0.2 },
            upper = { p -> BedRules.top(p) - 0.05 },
        ),
        ParamSpec(
            LEG, ParamKind.LENGTH, 0.0, 0.30,
            default = { 0.08 },
            upper = { p -> BedRules.top(p) - p.getValue(MATTRESS) - 0.05 },
        ),
        ParamSpec(HEADBOARD_T, ParamKind.LENGTH, 0.03, 0.15, default = { 0.06 }, appliesWhen = BedRules::hasHeadboard),
    ),
    presets = listOf(
        TemplatePreset("single", mapOf(W to 0.97, D to 1.98)),
        TemplatePreset("double", mapOf(W to 1.28, D to 1.98)),
        TemplatePreset("queen", mapOf(W to 1.58, D to 2.06)),
        TemplatePreset("king", mapOf(W to 1.88, D to 2.06)),
    ),
) {
    override fun parts(p: Params, b: PartBuilder) {
        val headboard = BedRules.hasHeadboard(p)
        val top = BedRules.top(p)
        val mattress = p.getValue(MATTRESS)
        val leg = p.getValue(LEG)
        val hb = if (headboard) p.getValue(HEADBOARD_T) else 0.0
        val head = b.back + hb // where the frame starts
        if (headboard) b.box("headboard", PartMaterial.FRAME, b.left, b.right, 0.0, b.h, b.back, head)
        b.box("frame", PartMaterial.FRAME, b.left, b.right, leg, top - mattress, head, b.front)
        val inset = 0.02
        b.box("mattress", PartMaterial.SOFT, b.left + inset, b.right - inset, top - mattress, top, head + inset, b.front - inset)
        if (headboard) {
            // Pillows sit on the mattress below the headboard's top (H − top ≥ 15 cm).
            val n = if (b.w >= 1.2) 2 else 1
            val ph = min(0.12, b.h - top)
            val pw = min(0.6, (b.w - 0.1) / n - 0.05)
            val pitch = b.w / n
            for (i in 0 until n) {
                val cx = b.left + pitch * (i + 0.5)
                b.box("pillow$i", PartMaterial.ACCENT, cx - pw / 2, cx + pw / 2, top, top + ph, head + 0.05, head + 0.40)
            }
        }
        if (leg > 0) cornerLegs(b, leg, head, b.front, size = 0.06)
    }
}
