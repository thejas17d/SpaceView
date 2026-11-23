package com.spaceview.app.pro

import kotlinx.coroutines.CancellationException

/**
 * The Free gallery cap (20 items) and the Free on-device 3D-scan quota (5 in total, ever: [deviceScanCount] is
 * the lifetime count, so deleting a scan does not free a slot), as one gate every new gallery item goes through (P1 review MAJOR 3, ADR-005):
 * - **before a scan starts** (Home / Gallery "Scan object"), so the paywall never opens inside AR;
 * - **again at Save** (a lapse, or another device, between entry and Save), keeping the user's draft;
 * - before `GalleryRepository.saveTemplate` (templates) and `saveMesh` (3D scan, which also checks
 *   [ProFeature.DEVICE_SCAN]).
 *
 * Only *new* items are gated: existing items, however many, stay viewable, placeable, shareable and
 * deletable after a lapse. If the gallery can't be counted, the save is allowed (we never block a user
 * on our own read error; the save itself reports any storage failure).
 */
class GallerySaveGate(
    private val gate: FeatureGate,
    private val galleryCount: suspend () -> Int,
    private val deviceScanCount: suspend () -> Int,
) {
    /** May one more gallery item (scan, template) be saved? */
    suspend fun checkItem(): GateState {
        val n = countOrNull(galleryCount) ?: return GateState.Allowed
        return gate.check(ProFeature.GALLERY_UNLIMITED, n)
    }

    /** May one more on-device 3D scan be saved? Both its own cap and the gallery cap apply. */
    suspend fun checkDeviceScan(): GateState {
        val item = checkItem()
        if (item is GateState.NeedsPro) return item
        val n = countOrNull(deviceScanCount) ?: return item
        return gate.check(ProFeature.DEVICE_SCAN, n)
    }

    private suspend fun countOrNull(count: suspend () -> Int): Int? = try {
        count()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The feature to open the paywall for when [state] needs Pro. */
    companion object {
        fun featureFor(state: GateState, deviceScan: Boolean = false): ProFeature =
            if (deviceScan && state is GateState.NeedsPro && state.limit == ProCatalog.FREE_DEVICE_SCANS) {
                ProFeature.DEVICE_SCAN
            } else {
                ProFeature.GALLERY_UNLIMITED
            }
    }
}
