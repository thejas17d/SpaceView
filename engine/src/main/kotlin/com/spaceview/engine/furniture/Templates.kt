package com.spaceview.engine.furniture

import com.spaceview.engine.Palette
import com.spaceview.engine.Part
import com.spaceview.engine.PartMaterial
import kotlin.math.roundToInt

/** Every furniture template, in picker order (the three Free core templates first). */
object Templates {
    val all: List<FurnitureTemplate> = listOf(
        SofaTemplate,
        BedTemplate,
        WardrobeTemplate,
        LSofaTemplate,
        DiningTableTemplate,
        DiningChairTemplate,
        TvUnitTemplate,
        BookshelfTemplate,
        StudyTableTemplate,
    )

    private val byId: Map<String, FurnitureTemplate> = all.associateBy { it.id }

    fun byId(id: String): FurnitureTemplate? = byId[id]

    /**
     * The parts of a stored item, or null when [templateId] is unknown or [params] are not valid for it (a corrupt
     * row: the gallery then shows the measured box). Unknown keys, such as the palette, are ignored.
     */
    fun partsOrNull(templateId: String, params: Params): List<Part>? {
        val t = byId(templateId) ?: return null
        val resolved = t.resolve(params)
        if (t.validate(resolved).isNotEmpty()) return null
        return runCatching { t.build(resolved) }.getOrNull()
    }
}

/**
 * The colour palettes a template item can be saved with. The chosen one is stored with the item's parameters under
 * [KEY] (its index), so a regenerated model keeps its colours. Indexes are stored: append, never reorder.
 */
object FurniturePalettes {
    /** Parameter key of the palette index (reserved: no template parameter may use it). */
    const val KEY = "palette"

    /** Stable names for the UI, by index. */
    val ids: List<String> = listOf("stone", "walnut", "indigo", "oak", "charcoal")

    private val palettes: List<Palette> = listOf(
        // Stone: the default neutral greys and wood (Palette.DEFAULT).
        Palette.DEFAULT,
        // Walnut: dark wood, beige upholstery.
        Palette(
            mapOf(
                PartMaterial.BODY to 0x6B4A35, PartMaterial.SOFT to 0xCDBEA4, PartMaterial.FRAME to 0x5A3B28,
                PartMaterial.LEG to 0x2E2520, PartMaterial.DOOR to 0x7A563E, PartMaterial.ACCENT to 0xB89B6A,
            ),
        ),
        // Indigo: blue fabric, light wood.
        Palette(
            mapOf(
                PartMaterial.BODY to 0x2F3E66, PartMaterial.SOFT to 0x4A5F94, PartMaterial.FRAME to 0xB08D63,
                PartMaterial.LEG to 0x3A2E24, PartMaterial.DOOR to 0x3D4F7E, PartMaterial.ACCENT to 0xD9C9A8,
            ),
        ),
        // Oak and white: light oak frame, white fronts.
        Palette(
            mapOf(
                PartMaterial.BODY to 0xC9A77C, PartMaterial.SOFT to 0xE8E2D6, PartMaterial.FRAME to 0xB8925F,
                PartMaterial.LEG to 0x8C6B46, PartMaterial.DOOR to 0xF2F0EB, PartMaterial.ACCENT to 0x8A8A8A,
            ),
        ),
        // Charcoal: dark greys with steel accents.
        Palette(
            mapOf(
                PartMaterial.BODY to 0x3B3C3F, PartMaterial.SOFT to 0x55575C, PartMaterial.FRAME to 0x2A2B2D,
                PartMaterial.LEG to 0x1C1C1E, PartMaterial.DOOR to 0x4A4C50, PartMaterial.ACCENT to 0xA7ADB4,
            ),
        ),
    )

    init {
        check(ids.size == palettes.size)
    }

    val count: Int get() = palettes.size

    /** The palette at [index]; anything out of range is the default (index 0). */
    fun of(index: Int): Palette = palettes.getOrElse(index) { palettes[0] }

    /** The palette stored in [params] (default when absent or invalid). */
    fun fromParams(params: Params): Palette = of(indexOf(params))

    /** The palette index stored in [params], 0 when absent or not a valid index. */
    fun indexOf(params: Params): Int {
        val v = params[KEY] ?: return 0
        if (!v.isFinite()) return 0
        val i = v.roundToInt()
        return if (i in palettes.indices) i else 0
    }
}
