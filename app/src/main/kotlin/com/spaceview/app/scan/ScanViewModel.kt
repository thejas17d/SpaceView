package com.spaceview.app.scan

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spaceview.app.scan.imaging.CutoutChoice
import com.spaceview.app.scan.imaging.CutoutMaker
import com.spaceview.app.scan.imaging.CutoutStatus
import com.spaceview.app.scan.imaging.FrontPhotoRectifier
import com.spaceview.app.scan.imaging.JpegEncoder
import com.spaceview.app.scan.imaging.SubjectSegmenter
import com.spaceview.app.settings.SettingsRepository
import com.spaceview.app.smart.NameSuggestion
import com.spaceview.app.smart.ObjectNamer
import com.spaceview.ar.FrontPhoto
import com.spaceview.ar.ImprovementTip
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.data.gallery.CutoutDraft
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.data.gallery.ScanDraft
import com.spaceview.data.settings.MeasureSettingsRepository
import com.spaceview.engine.ArgbImage
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
import kotlinx.coroutines.withTimeoutOrNull
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
    val depthEnabled: Boolean = true,
    /** Smart Measure hands-free point drop (SM1a), from the `spaceview_measure` store. */
    val handsFreeEnabled: Boolean = true,
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
 * @property photo the picture the Review shows and Save stores: the background-free cutout, or the rectified front
 *   photo (a flat object, "Undo cutout", or a cutout that could not be made).
 * @property photoBusy the photo is still being straightened or its background removed (Save waits for it).
 * @property removingBackground the subject segmentation is running (PLAN v3 W2).
 * @property cutoutStatus what happened to the background (null before a photo).
 * @property cutoutUndone the user chose "Undo cutout": the rectangle is saved although a cutout exists.
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
    /** Save was refused by the Free gallery cap; the draft is kept (Upgrade / Share / Discard). */
    val galleryFull: Boolean = false,
    /** SM1b: a curated name for the photo, offered as a chip; only applied when the user taps it. */
    val nameSuggestion: NameSuggestion? = null,
    /** Some outline points were dropped hands-free: saved as `CaptureMethod.HANDS_FREE`. */
    val handsFree: Boolean = false,
    val removingBackground: Boolean = false,
    val cutoutStatus: CutoutStatus? = null,
    val cutoutUndone: Boolean = false,
) {
    /** A background-free cutout exists (Undo / Redo cutout is offered). */
    val hasCutout: Boolean get() = cutoutStatus == CutoutStatus.REMOVED

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
    /**
     * The Free gallery cap at Save (Builder B, P1 review MAJOR 3): false means "needs Pro". The scan is then
     * kept on the Review screen, never dropped.
     */
    private val canSave: suspend () -> Boolean = { true },
    /** SM1b (contract K6): names the straightened photo once, off the main thread; null = no naming. */
    private val namer: ObjectNamer? = null,
    /** PLAN v3 W2: removes the background of a Box / Other photo; null = never (the rectangle is kept). */
    private val segmenter: SubjectSegmenter? = null,
) : ViewModel() {

    val settings: StateFlow<ScanSettings> = settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, ScanSettings())

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    /** The rectified front photo (opaque) and, for Box / Other, the background-free cutout ([CutoutChoice]). */
    private var pictures: CutoutChoice? = null
    private var rectangleBitmap: ImageBitmap? = null
    private var cutoutBitmap: ImageBitmap? = null
    private var photoJob: Job? = null
    private var nameJob: Job? = null

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

    /** The user confirmed the outline in AR; [handsFree]: some of its points were dropped hands-free (SM1a). */
    fun onOutline(outline: ScanOutline, tip: ImprovementTip?, tier: DeviceTier, handsFree: Boolean = false) {
        dropPhoto()
        setState { it.copy(outline = outline, tip = tip, tier = tier, handsFree = handsFree, edits = emptyMap(), step = ScanStep.PHOTO) }
    }

    /**
     * The front photo was taken: straighten it to the measured W:H off the main thread, then review. For Box / Other
     * the background is then removed (PLAN v3 W2): the straightened photo shows at once, the cutout replaces it when
     * ready, and Save waits for it. A flat object (frame, TV) is already exactly its corners: no segmentation.
     */
    fun onFrontPhoto(photo: FrontPhoto) {
        val outline = _state.value.outline
        val kind = _state.value.kind
        if (outline == null || kind == null || _state.value.step != ScanStep.PHOTO) {
            photo.bitmap.recycle()
            return
        }
        dropPhoto()
        setState { it.copy(step = ScanStep.REVIEW, photoBusy = true, photoFailed = false) }
        val aspect = outline.width.value / outline.height.value
        val flat = ScanDraftBuilder.shapeFor(kind) == com.spaceview.ar.ScanShape.FLAT
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
            if (result == null) {
                setState { it.copy(photoBusy = false, photoFailed = true) }
                return@launch
            }
            val (rectified, jpeg) = result
            val shown = rectified.asImageBitmap()
            rectangleBitmap = shown
            setState { it.copy(photo = shown, removingBackground = !flat && segmenter != null) }
            suggestName(jpeg)
            val choice = withContext(Dispatchers.Default) {
                val pixels = IntArray(rectified.width * rectified.height)
                rectified.getPixels(pixels, 0, rectified.width, 0, 0, rectified.width, rectified.height)
                val rect = ArgbImage(rectified.width, rectified.height, pixels)
                val mask = if (flat || segmenter == null) null else withTimeoutOrNull(SEGMENT_TIMEOUT_MS) { segmenter.mask(rectified) }
                val choice = CutoutMaker.choose(rect, flat, mask)
                val cut = choice.cutout?.let { img ->
                    runCatching { Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888).asImageBitmap() }.getOrNull()
                }
                // A cutout that can't be shown is not saved either (what the Review shows is what is stored).
                if (choice.cutout != null && cut == null) CutoutChoice(rect, null, CutoutStatus.NOT_AVAILABLE) to null else choice to cut
            }
            onPictures(choice.first, shown, choice.second)
        }
        // Cancelled before the block ran (Back, Measure again): the camera image is still ours to free (m6).
        job.invokeOnCompletion { cause -> if (cause != null) photo.bitmap.recycle() }
        photoJob = job
    }

    /**
     * The Review's pictures are ready ([choice] from [CutoutMaker]): shows the cutout when there is one (on a
     * checkerboard), else the rectangle, and says what happened to the background.
     */
    @VisibleForTesting
    internal fun onPictures(choice: CutoutChoice, rectangle: ImageBitmap?, cutout: ImageBitmap?) {
        pictures = choice
        rectangleBitmap = rectangle
        cutoutBitmap = cutout
        val removed = choice.status == CutoutStatus.REMOVED && choice.cutout != null
        setState {
            it.copy(
                photo = if (removed && cutout != null) cutout else rectangle,
                photoBusy = false,
                removingBackground = false,
                cutoutStatus = if (removed || choice.status != CutoutStatus.REMOVED) choice.status else CutoutStatus.NOT_SURE,
                cutoutUndone = false,
            )
        }
    }

    /** "Undo cutout" ([undo] = true) keeps the rectangle photo; false brings the background-free cutout back. */
    fun setCutoutUndone(undo: Boolean) {
        val s = _state.value
        if (!s.hasCutout || s.saving || s.savedId != null) return
        setState { it.copy(cutoutUndone = undo, photo = if (undo) rectangleBitmap else cutoutBitmap ?: rectangleBitmap) }
    }

    /** The picture Save stores: the cutout unless undone, else the rectangle; null without a photo. */
    private fun chosenPicture(s: ScanUiState): ArgbImage? {
        val p = pictures ?: return null
        return if (s.hasCutout && !s.cutoutUndone) p.cutout ?: p.rectangle else p.rectangle
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
        setState { it.copy(saving = true, saveFailed = false, galleryFull = false) }
        val draft = ScanDraftBuilder.draft(
            kind, values, CutoutDraft(chosenPicture(s)), ScanDraftBuilder.label(s.label, suggestedLabel), s.tier, arcoreVersion(), s.handsFree,
        )
        viewModelScope.launch {
            if (!canSave()) {
                // Gallery full on Free: keep everything (values, edits, name, photo) for Upgrade then Save.
                setState { it.copy(saving = false, galleryFull = true) }
                return@launch
            }
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

    /** Once per photo: the namer runs off main with its own timeout; a failure or "no model" just means no chip. */
    @VisibleForTesting
    internal fun suggestName(jpeg: ByteArray) {
        val n = namer ?: return
        val kind = _state.value.kind
        nameJob?.cancel()
        nameJob = viewModelScope.launch {
            val suggestion = try {
                n.suggest(jpeg, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // the contract says never, but a naming bug must never cost the user the scan
            }
            if (suggestion != null) _state.update { it.copy(nameSuggestion = suggestion) }
        }
    }

    private fun dropPhoto() {
        photoJob?.cancel()
        photoJob = null
        nameJob?.cancel()
        nameJob = null
        pictures = null
        rectangleBitmap = null
        cutoutBitmap = null
        _state.update {
            it.copy(
                photo = null, photoBusy = false, photoFailed = false, nameSuggestion = null,
                removingBackground = false, cutoutStatus = null, cutoutUndone = false,
            )
        }
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

        /** Longest wait for the subject segmentation; then the rectangle is kept ("couldn't remove the background"). */
        const val SEGMENT_TIMEOUT_MS = 10_000L

        /** The settings the scan screens use, from the app's two settings stores. */
        fun settingsFlow(measureSettings: MeasureSettingsRepository, appSettings: SettingsRepository): Flow<ScanSettings> = combine(
            measureSettings.settings,
            appSettings.unitPreference,
            appSettings.soundsEnabled,
        ) { m, units, sounds -> ScanSettings(units, sounds, m.depthEnabled, m.handsFreeEnabled, loaded = true) }
    }
}
