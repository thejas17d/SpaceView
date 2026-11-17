package com.spaceview.app.furniture

import com.spaceview.data.gallery.TemplateModelSource
import com.spaceview.engine.Palette
import com.spaceview.engine.Part
import com.spaceview.engine.furniture.FurniturePalettes
import com.spaceview.engine.furniture.Templates

/**
 * The furniture template library as the gallery's [TemplateModelSource] (M4 MINOR 11, gate T3): a stored item whose
 * model is stale (an older `glbVersion`) or missing is rebuilt from its template id and parameters, with the palette it
 * was saved with. An unknown id or invalid parameters give null, and the gallery then shows the measured box.
 *
 * Wired once in `AppContainer` (`GalleryRepository.create(appContext, templates = FurnitureTemplateRegistry)`).
 */
object FurnitureTemplateRegistry : TemplateModelSource {
    override fun parts(templateId: String, params: Map<String, Double>): List<Part>? = Templates.partsOrNull(templateId, params)

    override fun palette(templateId: String, params: Map<String, Double>): Palette? =
        if (Templates.byId(templateId) != null) FurniturePalettes.fromParams(params) else null
}
