package com.spaceview.app.scan

import android.graphics.PointF
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spaceview.app.scan.imaging.FrontPhotoRectifier
import com.spaceview.app.scan.imaging.JpegEncoder
import com.spaceview.app.settings.SettingsRepository
import com.spaceview.ar.FrontPhoto
import com.spaceview.ar.ImprovementTip
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.data.gallery.ScanDraft
import com.spaceview.data.settings.MeasureSettingsRepository
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Steps of the scan flow; the step indicator shows [number] of [COUNT] (Saved has none). */
enum class ScanStep(val number: Int) {
    TYPE(1), OUTLINE(2), PHOTO(3), REVIEW(4), SAVED(0);

    companion object {
        const val COUNT = 4
    }
}

/** Settings the scan screens need; [loaded] is false until DataStore answered. */
data class ScanSettings(
    val units: UnitPreference = UnitPreference.AUTO,
    val soundsEnabled: Boolean = true,
    val depthEnabled: Boolean = false,
    val loaded: Boolean = false,
)

/** What the scan flow needs from the gallery (a seam so the view model is JVM-tested). */
interface ScanStore {
    /** Stores the scan; throws when nothing could be saved. */
    suspend fun save(draft: ScanDraft): Long
    suspend fun get(id: Long): GalleryItem?
    suspend fun shareCopy(id: Long, fileName: String): File?
}

/** [ScanStore] on the real gallery. */
class GalleryScanStore(private val gallery: GalleryRepository) : ScanStore {
    override suspend fun save(draft: ScanDraft): Long = gallery.save(draft)
    override suspend fun get(id: Long): GalleryItem? = gallery.get(id)
    override suspend fun shareCopy(id: Long, fileName: String): File? = gallery.shareCopy(id, fileName)
}

/**
 * State of the scan flow. Holds no ARCore objects (the AR steps own their `MeasureSession`).
 *
 * @property outline the measured outline (from the reading the user confirmed in AR).
 * @property photo the rectified front photo as shown on the review (also the stored JPEG).
 * @property photoBusy the photo is still being straightened / encoded (Save waits for it).
 */
data class ScanUiState(
    val step: ScanStep = ScanStep.TYPE,
    val kind: ObjectKind? = null,
    val outline: ScanOutline? = null,
    val tip: ImprovementTip? = null,
    val tier: DeviceTier = DeviceTier.NO_DEPTH,
    val edits: Map<ScanDim, Double> = emptyMap(),
    val label: String? = null,
    val photo: ImageBitmap? = null,
    val photoBusy: Boolean = false,
    val photoFailed: Boolean = false,
    val saving: Boolean = false,
    val savedId: Long? = null,
    val savedItem: GalleryItem? = null,
    val saveFailed: Boolean = false,
) {
    val shape: ScanShape? get() = kind?.let { ScanDraftBuilder.shapeFor(it) }

    /** Review values (null until the outline exists). */
    val values: ScanValues? get() {
        val k = kind ?: return null
        val o = outline ?: return null
        return ScanDraftBuilder.values(k, o, edits)
    }

    /** Leaving the Review would throw away something the user did (edits, a name, a photo): ask first. */
    val hasReviewWork: Boolean get() = edits.isNotEmpty() || label != null || photo != null || photoBusy
}

/**
 * The scan flow's state machine and persistence (manual DI). Photo straightening and JPEG encoding run
 * on [Dispatchers.Default]; the gallery write in the store. Storage failures are reported, never thrown.
 *
 * Process death: the step, the kind and the saved id are kept in [savedState]; a flow that was saved
 * comes back on the Saved screen, one that was still measuring restarts its outline (ARCore anchors
 * cannot survive). [onScanSaved] is called exactly once per stored scan, from the save itself, so the
 * ad cap never misses a scan (M3 review m5).
 */
class ScanViewModel(
    private val savedState: SavedStateHandle,
    settingsFlow: Flow<ScanSettings>,
    private val store: ScanStore,
    private val arcoreVersion: () -> String?,
    private val onScanSaved: () -> Unit,
) : ViewModel() {

    val settings: StateFlow<ScanSettings> = settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, ScanSettings())

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var frontJpeg: ByteArray? = null
    private var photoJob: Job? = null

    init {
        _state.value.savedId?.let { loadSavedItem(it) }
    }

    private fun restore(): ScanUiState {
        val kind = savedState.get<String>(KEY_KIND)?.let { n -> ObjectKind.entries.firstOrNull { it.name == n } }
        val savedId = savedState.get<Long>(KEY_SAVED_ID)
        return when {
            kind != null && savedId != null -> ScanUiState(step = ScanStep.SAVED, kind = kind, savedId = savedId)
            kind != null -> ScanUiState(step = ScanStep.OUTLINE, kind = kind)
            else -> ScanUiState()
        }
    }

    private fun setState(transform: (ScanUiState) -> ScanUiState) {
        _state.update(transform)
        val s = _state.value
        savedState[KEY_KIND] = s.kind?.name
        savedState[KEY_SAVED_ID] = s.savedId
    }

    fun chooseKind(kind: ObjectKind) {
        setState { it.copy(kind = kind, step = ScanStep.OUTLINE, outline = null, edits = emptyMap()) }
    }

    /** The user confirmed the outline in AR. */
    fun onOutline(outline: ScanOutline, tip: ImprovementTip?, tier: DeviceTier) {
        dropPhoto()
        setState { it.copy(outline = outline, tip = tip, tier = tier, edits = emptyMap(), step = ScanStep.PHOTO) }
    }

    /** The front photo was taken: straighten it to the measured W:H off the main thread, then review. */
    fun onFrontPhoto(photo: FrontPhoto) {
        val outline = _state.value.outline
        if (outline == null || _state.value.step != ScanStep.PHOTO) {
            photo.bitmap.recycle()
            return
        }
        dropPhoto()
        setState { it.copy(step = ScanStep.REVIEW, photoBusy = true, photoFailed = false) }
        val aspect = outline.width.value / outline.height.value
        val job = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val c = photo.cornersPx
                    val corners = List(4) { PointF(c[it * 2], c[it * 2 + 1]) }
                    val rectified = FrontPhotoRectifier.rectify(photo.bitmap, corners, aspect, PHOTO_LONG_SIDE)
                    val jpeg = JpegEncoder.encode(rectified, JpegEncoder.SCAN_QUALITY)
                    rectified to jpeg
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("Front photo not usable: ${e.message}")
                    null
                } catch (e: OutOfMemoryError) {
                    null
                } finally {
                    photo.bitmap.recycle()
                }
            }
            frontJpeg = result?.second
            setState { it.copy(photo = result?.first?.asImageBitmap(), photoBusy = false, photoFailed = result == null) }
        }
        // Cancelled before the block ran (Back, Measure again): the camera image is still ours to free (m6).
        job.invokeOnCompletion { cause -> if (cause != null) photo.bitmap.recycle() }
        photoJob = job
    }

    /** Continue without a photo (the gallery item gets a plain-colour front). */
    fun skipPhoto() {
        dropPhoto()
        setState { it.copy(step = ScanStep.REVIEW) }
    }

    fun edit(dim: ScanDim, meters: Double?) {
        setState { s -> s.copy(edits = if (meters == null) s.edits - dim else s.edits + (dim to meters)) }
    }

    fun setLabel(label: String) {
        setState { it.copy(label = label.take(GalleryRepository.MAX_LABEL)) }
    }

    /** Back from the photo step to the outline (points are kept). */
    fun backToOutline() {
        dropPhoto()
        setState { it.copy(step = ScanStep.OUTLINE) }
    }

    /** Measure again: a fresh outline (the AR session starts over). Ignored while a save is running. */
    fun remeasure() {
        if (_state.value.saving || _state.value.savedId != null) return
        dropPhoto()
        setState { it.copy(step = ScanStep.OUTLINE, outline = null, edits = emptyMap(), label = null, saveFailed = false) }
    }

    fun backToType() {
        if (_state.value.saving) return
        dropPhoto()
        setState { ScanUiState() }
    }

    /** Saves once; [suggestedLabel] is the default name shown in the name field. */
    fun save(suggestedLabel: String) {
        val s = _state.value
        val kind = s.kind ?: return
        val values = s.values ?: return
        if (s.saving || s.savedId != null || s.photoBusy || !values.isSavable) return
        setState { it.copy(saving = true, saveFailed = false) }
        val draft = ScanDraftBuilder.draft(kind, values, frontJpeg, ScanDraftBuilder.label(s.label, suggestedLabel), s.tier, arcoreVersion())
        viewModelScope.launch {
            val id = try {
                store.save(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("Scan not saved: ${e.message}")
                null
            }
            if (id == null) {
                setState { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            setState { it.copy(saving = false, savedId = id, step = ScanStep.SAVED) }
            onScanSaved()
            loadSavedItem(id)
        }
    }

    /** A share copy of the saved item's photo (the gallery's own share path). */
    suspend fun shareCopy(id: Long, fileName: String): File? = store.shareCopy(id, fileName)

    private fun loadSavedItem(id: Long) {
        viewModelScope.launch {
            val item = try { store.get(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            _state.update { it.copy(savedItem = item) }
        }
    }

    private fun dropPhoto() {
        photoJob?.cancel()
        photoJob = null
        frontJpeg = null
        _state.update { it.copy(photo = null, photoBusy = false, photoFailed = false) }
    }

    private fun warn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    companion object {
        private const val TAG = "ScanViewModel"
        private const val KEY_KIND = "scan_kind"
        private const val KEY_SAVED_ID = "scan_saved_id"

        /** Long side of the straightened photo (the gallery re-encodes it within its size budget). */
        const val PHOTO_LONG_SIDE = 1024

        /** The settings the scan screens use, from the app's two settings stores. */
        fun settingsFlow(measureSettings: MeasureSettingsRepository, appSettings: SettingsRepository): Flow<ScanSettings> = combine(
            measureSettings.settings,
            appSettings.unitPreference,
            appSettings.soundsEnabled,
        ) { m, units, sounds -> ScanSettings(units, sounds, m.depthEnabled, loaded = true) }
    }
}
