package com.spaceview.app.pro

import com.spaceview.data.gallery.TemplateDraft

/** The outcome of a gated gallery insert. */
sealed interface GatedSave {
    data class Saved(val id: Long) : GatedSave

    /** Nothing was written: [feature] needs Pro. The caller keeps its draft and offers the paywall (K5). */
    data class NeedsPro(val feature: ProFeature) : GatedSave
}

/**
 * The one gated insert path for new gallery items that are not proxy scans (gate §8 T2, and D4 for meshes later): every
 * `saveTemplate` goes through here, never straight to the repository.
 *
 * Order of the checks, which is also the order a user meets them: the template itself (a non-core template needs Pro,
 * [ProFeature.FURNITURE_TEMPLATES]), then the Free gallery cap ([GallerySaveGate.checkItem]). The same checks run at
 * the picker ([templateBlocker], before any AR) and again at Save (a lapse between the two keeps the user's draft).
 *
 * @param saveTemplateTo the repository's `saveTemplate` (throws when nothing could be saved).
 */
class GatedGalleryStore(
    private val featureGate: FeatureGate,
    private val saveGate: GallerySaveGate,
    private val saveTemplateTo: suspend (TemplateDraft) -> Long,
) {
    /** The feature that blocks starting template [templateId] (null = it may start). */
    suspend fun templateBlocker(templateId: String): ProFeature? {
        if (featureGate.check(ProFeature.FURNITURE_TEMPLATES, itemId = templateId) is GateState.NeedsPro) {
            return ProFeature.FURNITURE_TEMPLATES
        }
        if (saveGate.checkItem() is GateState.NeedsPro) return ProFeature.GALLERY_UNLIMITED
        return null
    }

    /** Saves [draft] when both gates allow it. Throws (like the repository) when storage fails. */
    suspend fun saveTemplate(draft: TemplateDraft): GatedSave {
        templateBlocker(draft.templateId)?.let { return GatedSave.NeedsPro(it) }
        return GatedSave.Saved(saveTemplateTo(draft))
    }
}
