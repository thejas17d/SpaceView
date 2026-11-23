package com.spaceview.app.settings

import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.history.MeasurementRepository
import com.spaceview.data.settings.MeasureSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What Delete my data removes, one flag per store, so a partial failure can be reported and retried. */
data class EraseResult(
    val history: Boolean,
    val gallery: Boolean,
    val measureSettings: Boolean,
    val appSettings: Boolean,
) {
    val complete: Boolean get() = history && gallery && measureSettings && appSettings
}

/**
 * Settings → **Delete my data** (M5, PLAN §9 privacy). Removes everything SpaceView keeps about the user's
 * measuring, on this phone:
 * - History (every saved measurement),
 * - the Gallery (rows, models, photos, thumbnails, and SpaceView's own cache folders: share copies and Place boxes),
 * - calibration and the other measuring settings (depth, the safety notice),
 * - app settings (units, sounds; onboarding stays finished).
 *
 * Deliberately **not** touched:
 * - the Play subscription and the cached Pro entitlement (Play owns the subscription; deleting the cache would only
 *   show Free until Play answers again, and could briefly start ads for a paying user);
 * - ad frequency counters and the review-prompt record (they only limit how often we interrupt; resetting them
 *   would bring back interruptions sooner);
 * - the ad-consent choice (it is changed through Privacy options, which stays available);
 * - caches that belong to SDKs (WebView, Play services, ML Kit, AdMob). They hold no user content, an ad may be on
 *   screen while this runs, and an SDK recreating its folder would make the result look like a failure (SM1-M5
 *   review MINOR 11). SpaceView's own cache content (`cache/share`, `cache/place`) goes with the Gallery.
 *
 * Every step runs even if an earlier one fails, and the whole erase finishes even if the screen that started it
 * goes away (a half-erased phone is worse than a finished one). Repositories are main-safe.
 */
class UserDataEraser(
    private val history: MeasurementRepository,
    private val gallery: GalleryRepository,
    private val measureSettings: MeasureSettingsRepository,
    private val appSettings: SettingsRepository,
) {
    suspend fun eraseAll(): EraseResult = withContext(NonCancellable) {
        EraseResult(
            history = history.clearAll(),
            gallery = gallery.deleteAll(),
            measureSettings = step { measureSettings.clearAll() },
            appSettings = step { appSettings.resetPreferences() },
        )
    }

    private suspend fun step(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
