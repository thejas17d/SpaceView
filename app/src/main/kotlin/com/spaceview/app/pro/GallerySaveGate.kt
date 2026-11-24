package com.spaceview.app.pro

import kotlinx.coroutines.CancellationException

/**
 * The Free gallery cap (20 items), as one gate every new gallery item goes through (P1 review MAJOR 3, ADR-005):
 * - **before a scan starts** (Home / Gallery "Scan object"), so the paywall never opens inside AR;
 * - **again at Save** (a lapse, or another device, between entry and Save), keeping the user's draft.
 *
 * Only *new* items are gated: existing items, however many, stay viewable, placeable, shareable and
 * deletable after a lapse. If the gallery can't be counted, the save is allowed (we never block a user
 * on our own read error; the save itself reports any storage failure).
 */
class GallerySaveGate(
    private val gate: FeatureGate,
    private val galleryCount: suspend () -> Int,
) {
    /** May one more gallery item be saved? */
    suspend fun checkItem(): GateState {
        val n = try {
            galleryCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return GateState.Allowed
        }
        return gate.check(ProFeature.GALLERY_UNLIMITED, n)
    }
}
