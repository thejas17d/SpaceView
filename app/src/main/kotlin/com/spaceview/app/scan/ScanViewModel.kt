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
import com.spaceview.app.scan.imaging.CornerSnap
import com.spaceview.app.scan.imaging.CutoutStatus
import com.spaceview.app.scan.imaging.FrontPhotoRectifier
import com.spaceview.app.scan.imaging.JpegEncoder
import com.spaceview.app.scan.imaging.QuarterTurns
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
import com.spaceview.engine.CaptureCamera
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

/**
 * Keeps the Review's camera photo across process death (PLAN v3.1 W8), in the app's cache, never in Room. Keyed per
 * capture; removed when the Review ends (save, discard, a new photo, or the flow being closed).
 */
interface ReviewPhotoStore {
    fun put(key: String, jpeg: ByteArray)
    fun get(key: String): ByteArray?
    fun remove(key: String)

    /** Deletes every kept photo except [keep] (stale ones from a Review that ended without save or discard). */
    fun prune(keep: String?)
}

/**
 * [ReviewPhotoStore] in a cache directory (one file per capture). Never throws. Every operation holds one lock, so a
 * slow write and a remove of the same key never interleave (PLAN v3.1 gate v9).
 */
class FileReviewPhotoStore(private val dir: File) : ReviewPhotoStore {
    private fun file(key: String) = File(dir, key.filter { it.isLetterOrDigit() || it == '-' } + ".jpg")

    @Synchronized
    override fun put(key: String, jpeg: ByteArray) {
        runCatching {
            dir.mkdirs()
            // Only the current capture is ever needed: drop older ones (a crash may have left some).
            dir.listFiles()?.forEach { it.delete() }
            file(key).writeBytes(jpeg)
        }
    }

    @Synchronized
    override fun get(key: String): ByteArray? = runCatching { file(key).takeIf { it.isFile }?.readBytes() }.getOrNull()

    @Synchronized
    override fun remove(key: String) {
        runCatching { file(key).delete() }
    }

    @Synchronized
    override fun prune(keep: String?) {
        val kept = keep?.let { file(it).name }
        runCatching { dir.listFiles()?.forEach { if (it.name != kept) it.delete() } }
    }
}

/**
 * The corner editor's picture (PLAN v3.1 W8): the capture photo turned upright ([turns]) and the corners in its
 * pixels (display frame).
 */
class EditorPhoto(val image: ImageBitmap, val turns: QuarterTurns, val corners: FloatArray, val autoCorners: FloatArray)

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
    /** PLAN v3.1 W8: the outline as measured in AR; [outline] is the one in use (re-measured when corners were adjusted). */
    val autoOutline: ScanOutline? = null,
    /** The face's corners in the capture photo (sensor pixels: TL, TR, BR, BL), as detected and measured. */
    val autoCorners: FloatArray? = null,
    /** The corners the user set ("Adjust corners"); null = Auto. */
    val adjustedCorners: FloatArray? = null,
    /** Per adjusted corner: the sub-pixel snap's σ (px), NaN when placed by hand (AimModel's error applies). */
    val cornerSnapSigma: FloatArray? = null,
    /** "Adjust corners" is possible: the capture photo and its camera are here. */
    val canAdjustCorners: Boolean = false,
    /** The corner editor is open with this picture. */
    val editor: EditorPhoto? = null,
    /** The last corners set in the editor could not be measured (a corner off the face plane, or a degenerate quad). */
    val cornersRefused: Boolean = false,
    /** The Review came back after the app was closed by the system; the photo may be missing. */
    val restored: Boolean = false,
) {
    /** The corners are the user's ("Adjust corners"), not the automatic ones. */
    val cornersAdjusted: Boolean get() = adjustedCorners != null

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
    /** PLAN v3.1 W8: keeps the Review's camera photo across process death; null = the photo is lost then. */
    private val photoStore: ReviewPhotoStore? = null,
) : ViewModel() {

    val settings: StateFlow<ScanSettings> = settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, ScanSettings())

    // Declared before _state: restore() reads them.

    /** PLAN v3.1 W8: the capture photo (sensor orientation) kept for re-rectifying with adjusted corners. */
    private var source: Bitmap? = null
    private var camera: CaptureCamera? = savedState.get<DoubleArray>(KEY_CAMERA)?.let { ReviewSnapshot.decodeCamera(it) }
    @Volatile private var photoKey: String? = savedState.get<String>(KEY_PHOTO)
    @Volatile private var snapper: CornerSnap? = null

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
        if (_state.value.restored) restorePhoto()
        // Gate v8: the kept photo is not deleted in onCleared (that also runs for "Don't keep activities", whose
        // Review then comes back): stale photos of Reviews that ended without save or discard go here instead.
        photoStore?.let { ps ->
            val keep = photoKey.takeIf { _state.value.restored }
            viewModelScope.launch(Dispatchers.IO) { runCatching { ps.prune(keep) } }
        }
    }

    private fun restore(): ScanUiState {
        val kind = savedState.get<String>(KEY_KIND)?.let { n -> ObjectKind.entries.firstOrNull { it.name == n } }
        val savedId = savedState.get<Long>(KEY_SAVED_ID)
        val review = if (kind != null && savedId == null && savedState.get<String>(KEY_STEP) == ScanStep.REVIEW.name) restoreReview(kind) else null
        return when {
            kind != null && savedId != null -> ScanUiState(step = ScanStep.SAVED, kind = kind, savedId = savedId)
            review != null -> review
            kind != null -> ScanUiState(step = ScanStep.OUTLINE, kind = kind)
            else -> ScanUiState()
        }
    }

    /**
     * A Review that the system closed (PLAN v3.1 W8): the measured outline, the corners (automatic and adjusted, so an
     * adjustment survives), the camera, edits, name and choices come back from [savedState]; the photo from
     * [photoStore] ([restorePhoto]). Null when the snapshot is incomplete (the flow then restarts its outline).
     */
    private fun restoreReview(kind: ObjectKind): ScanUiState? {
        val auto = ReviewSnapshot.decodeOutline(savedState.get<DoubleArray>(KEY_OUTLINE)) ?: return null
        val tier = savedState.get<String>(KEY_TIER)?.let { n -> DeviceTier.entries.firstOrNull { it.name == n } } ?: DeviceTier.NO_DEPTH
        val autoCorners = ReviewSnapshot.validCorners(savedState.get<FloatArray>(KEY_CORNERS_AUTO))
        val adjusted = ReviewSnapshot.validCorners(savedState.get<FloatArray>(KEY_CORNERS_ADJ))
        val cam = camera
        val snapSigma = savedState.get<FloatArray>(KEY_CORNER_SIGMA)?.takeIf { it.size == 4 }
        val adjustedOutline = if (adjusted != null && cam != null) CornerAdjust.outline(auto, adjusted, cam, tier, snapSigma, autoCorners) else null
        val hasPhoto = photoKey != null && photoStore != null
        return ScanUiState(
            step = ScanStep.REVIEW,
            kind = kind,
            outline = adjustedOutline ?: auto,
            autoOutline = auto,
            tip = savedState.get<String>(KEY_TIP)?.let { n -> ImprovementTip.entries.firstOrNull { it.name == n } },
            tier = tier,
            edits = ReviewSnapshot.decodeEdits(savedState.get<DoubleArray>(KEY_EDITS)),
            label = savedState.get<String>(KEY_LABEL),
            handsFree = savedState.get<Boolean>(KEY_HANDS_FREE) ?: false,
            autoCorners = autoCorners,
            adjustedCorners = adjusted.takeIf { adjustedOutline != null },
            cornerSnapSigma = snapSigma.takeIf { adjustedOutline != null },
            cutoutUndone = savedState.get<Boolean>(KEY_CUTOUT_UNDONE) ?: false,
            photoBusy = hasPhoto,
            restored = true,
        )
    }

    private fun setState(transform: (ScanUiState) -> ScanUiState) {
        _state.update(transform)
        val s = _state.value
        savedState[KEY_KIND] = s.kind?.name
        savedState[KEY_SAVED_ID] = s.savedId
        // PLAN v3.1 W8: the Review (with its adjusted corners) survives process death; plain numbers only.
        savedState[KEY_STEP] = s.step.name
        savedState[KEY_OUTLINE] = s.autoOutline?.let { ReviewSnapshot.encodeOutline(it) }
        savedState[KEY_CORNERS_AUTO] = s.autoCorners
        savedState[KEY_CORNERS_ADJ] = s.adjustedCorners
        savedState[KEY_CORNER_SIGMA] = s.cornerSnapSigma
        savedState[KEY_TIER] = s.tier.name
        savedState[KEY_TIP] = s.tip?.name
        savedState[KEY_EDITS] = ReviewSnapshot.encodeEdits(s.edits)
        savedState[KEY_LABEL] = s.label
        savedState[KEY_HANDS_FREE] = s.handsFree
        savedState[KEY_CUTOUT_UNDONE] = s.cutoutUndone
        savedState[KEY_CAMERA] = camera?.let { ReviewSnapshot.encodeCamera(it) }
        savedState[KEY_PHOTO] = photoKey
    }

    fun chooseKind(kind: ObjectKind) {
        setState { it.copy(kind = kind, step = ScanStep.OUTLINE, outline = null, autoOutline = null, edits = emptyMap()) }
    }

    /** The user confirmed the outline in AR; [handsFree]: some of its points were dropped hands-free (SM1a). */
    fun onOutline(outline: ScanOutline, tip: ImprovementTip?, tier: DeviceTier, handsFree: Boolean = false) {
        dropPhoto()
        setState {
            it.copy(outline = outline, autoOutline = outline, tip = tip, tier = tier, handsFree = handsFree, edits = emptyMap(), step = ScanStep.PHOTO)
        }
    }

    /**
     * The front photo was taken: keep it (with its camera, for "Adjust corners"), straighten it to the measured W:H off
     * the main thread, then review. For Box / Other the background is then removed (PLAN v3 W2): the straightened
     * photo shows at once, the cutout replaces it when ready, and Save waits for it. A flat object (frame, TV) is
     * already exactly its corners: no segmentation.
     */
    fun onFrontPhoto(photo: FrontPhoto) {
        val outline = _state.value.outline
        val kind = _state.value.kind
        if (outline == null || kind == null || _state.value.step != ScanStep.PHOTO) {
            photo.bitmap.recycle()
            return
        }
        dropPhoto()
        source = photo.bitmap
        val auto = photo.cornersPx.copyOf()
        onCapture(auto, photo.camera)
        keepPhoto(photo.bitmap)
        processPicture(auto, nameIt = true)
    }

    /** The capture's corners and camera (W8); the Review starts on Auto. */
    @VisibleForTesting
    internal fun onCapture(autoCorners: FloatArray, captureCamera: CaptureCamera?) {
        val outline = _state.value.outline ?: return
        camera = captureCamera
        setState {
            it.copy(
                step = ScanStep.REVIEW, autoOutline = it.autoOutline ?: outline, autoCorners = autoCorners, adjustedCorners = null, cornerSnapSigma = null,
                canAdjustCorners = captureCamera != null, cornersRefused = false, restored = false,
            )
        }
    }

    /** Saves the capture photo for process death (a JPEG in the cache, off the main thread). */
    private fun keepPhoto(bitmap: Bitmap) {
        val ps = photoStore ?: return
        val key = java.util.UUID.randomUUID().toString()
        photoKey = key
        setState { it }
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { JpegEncoder.encode(bitmap, JpegEncoder.SCAN_QUALITY) }.getOrNull()?.let { keepIfCurrent(ps, key, it) }
        }
    }

    /**
     * Writes the photo, then deletes it again if the Review moved on meanwhile (Save, Back, Measure again, a new
     * photo): a slow write can never leave a photo behind (gate v9).
     */
    @VisibleForTesting
    internal fun keepIfCurrent(ps: ReviewPhotoStore, key: String, jpeg: ByteArray) {
        ps.put(key, jpeg)
        if (photoKey != key) ps.remove(key)
    }

    /** After process death: the kept capture photo, decoded, then straightened with the current corners. */
    private fun restorePhoto() {
        val ps = photoStore
        val key = photoKey
        val corners = _state.value.adjustedCorners ?: _state.value.autoCorners
        if (ps == null || key == null || corners == null) {
            _state.update { it.copy(photoBusy = false) }
            return
        }
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                runCatching { ps.get(key)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
            }
            if (bitmap == null) {
                _state.update { it.copy(photoBusy = false, canAdjustCorners = false) }
                return@launch
            }
            source = bitmap
            _state.update { it.copy(canAdjustCorners = camera != null) }
            processPicture(corners, nameIt = false)
        }
    }

    /**
     * Straightens the kept capture photo with [corners] (sensor pixels) to the outline's W:H, then removes the
     * background for Box / Other. Used for the first picture, after "Adjust corners" and after "Reset to auto".
     */
    private fun processPicture(corners: FloatArray, nameIt: Boolean) {
        val src = source ?: return
        val s0 = _state.value
        val kind = s0.kind ?: return
        val outline = s0.outline ?: return
        clearPictures()
        setState { it.copy(photoBusy = true, photoFailed = false) }
        val aspect = outline.width.value / outline.height.value
        val flat = ScanDraftBuilder.shapeFor(kind) == com.spaceview.ar.ScanShape.FLAT
        val keepUndone = s0.cutoutUndone
        photoJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val c = List(4) { PointF(corners[it * 2], corners[it * 2 + 1]) }
                    val rectified = FrontPhotoRectifier.rectify(src, c, aspect, PHOTO_LONG_SIDE)
                    val jpeg = if (nameIt) JpegEncoder.encode(rectified, JpegEncoder.SCAN_QUALITY) else null
                    rectified to jpeg
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("Front photo not usable: ${e.message}")
                    null
                } catch (e: OutOfMemoryError) {
                    null
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
            jpeg?.let { suggestName(it) }
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
            if (keepUndone && _state.value.hasCutout) setCutoutUndone(true)
        }
    }

    // ---- "Adjust corners" (PLAN v3.1 W8) ----

    /** Opens the corner editor: the capture photo turned upright, with the corners in use. */
    fun openCornerEditor() {
        val src = source ?: return
        val s = _state.value
        val auto = s.autoCorners ?: return
        if (!s.canAdjustCorners || s.saving || s.savedId != null) return
        viewModelScope.launch {
            val editor = withContext(Dispatchers.Default) {
                runCatching {
                    val turns = QuarterTurns.upright(auto, src.width, src.height)
                    val m = android.graphics.Matrix().apply { postRotate(90f * turns.turns) }
                    val turned = if (turns.turns == 0) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                    if (snapper == null) {
                        val px = IntArray(src.width * src.height)
                        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
                        snapper = CornerSnap(src.width, src.height, CornerSnap.luma(px))
                    }
                    val current = _state.value.adjustedCorners ?: auto
                    EditorPhoto(turned.asImageBitmap(), turns, turns.cornersToDisplay(current), turns.cornersToDisplay(auto))
                }.getOrNull()
            }
            if (editor != null) _state.update { it.copy(editor = editor, cornersRefused = false) }
        }
    }

    fun closeCornerEditor() {
        _state.update { it.copy(editor = null, cornersRefused = false) }
    }

    /** Snap a dragged corner (sensor pixels) to a nearby image corner; null = leave it where it is. */
    /**
     * Snaps corner [k] of [corners] (sensor pixels, TL, TR, BR, BL) to the photo's real corner: W9's sub-pixel refiner
     * along its two sides, else a Shi–Tomasi corner. Null = leave it where it is.
     */
    fun snapCorner(corners: FloatArray, k: Int): com.spaceview.app.scan.imaging.SnappedCorner? {
        if (corners.size != 8 || k !in 0..3) return null
        fun at(i: Int) = corners[2 * ((i + 4) % 4)] to corners[2 * ((i + 4) % 4) + 1]
        return snapper?.snap(corners[2 * k], corners[2 * k + 1], prev = at(k - 1), next = at(k + 1))
    }

    /**
     * The user's corners (sensor pixels: TL, TR, BR, BL as seen from the front). Width and height are re-measured from
     * geometry ([CornerAdjust]); the picture is straightened again with them. Refused (the editor stays open and says
     * so) when they cannot be measured. The automatic corners themselves mean "Auto". [snapSigmaPx]: per corner, the
     * sub-pixel snap's σ (NaN = placed by hand).
     */
    fun applyCorners(corners: FloatArray, snapSigmaPx: FloatArray? = null) {
        val s = _state.value
        val auto = s.autoOutline ?: return
        val autoCorners = s.autoCorners ?: return
        val cam = camera ?: return
        if (s.saving || s.savedId != null || corners.size != 8) return
        if (!CornerSnap.differ(corners, autoCorners)) {
            resetCornersToAuto()
            return
        }
        val sigma = snapSigmaPx?.takeIf { it.size == 4 }?.copyOf()
        val adjusted = CornerAdjust.outline(auto, corners, cam, s.tier, sigma, autoCorners)
        if (adjusted == null) {
            _state.update { it.copy(cornersRefused = true) }
            return
        }
        setState { it.copy(outline = adjusted, adjustedCorners = corners.copyOf(), cornerSnapSigma = sigma, editor = null, cornersRefused = false) }
        processPicture(corners, nameIt = false)
    }

    /** The width and height (m) [corners] (sensor pixels) would measure, for the editor's TalkBack status; null if they can't. */
    fun previewCorners(corners: FloatArray): Pair<Double, Double>? {
        val s = _state.value
        val auto = s.autoOutline ?: return null
        val cam = camera ?: return null
        if (corners.size != 8) return null
        val o = CornerAdjust.outline(auto, corners, cam, s.tier, null, s.autoCorners) ?: return null
        return o.width.value to o.height.value
    }

    /** "Reset to auto": the measured outline and the automatic corners again. */
    fun resetCornersToAuto() {
        val s = _state.value
        val auto = s.autoOutline ?: return
        val autoCorners = s.autoCorners ?: return
        if (s.saving || s.savedId != null) return
        val wasAdjusted = s.adjustedCorners != null
        setState { it.copy(outline = auto, adjustedCorners = null, cornerSnapSigma = null, editor = null, cornersRefused = false) }
        if (wasAdjusted) processPicture(autoCorners, nameIt = false)
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
        setState { it.copy(step = ScanStep.REVIEW, outline = it.autoOutline ?: it.outline) }
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
        setState { it.copy(step = ScanStep.OUTLINE, outline = null, autoOutline = null, edits = emptyMap(), label = null, saveFailed = false) }
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
            forgetPhoto()
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


    /** Deletes the process-death copy of the capture photo. */
    private fun forgetPhoto() {
        val key = photoKey ?: return
        photoKey = null
        savedState[KEY_PHOTO] = null
        photoStore?.let { ps -> runCatching { ps.remove(key) } }
    }

    /** Drops the capture photo, its camera and corners, and the Review's pictures. */
    private fun dropPhoto() {
        clearPictures()
        nameJob?.cancel()
        nameJob = null
        source = null
        camera = null
        snapper = null
        forgetPhoto()
        _state.update {
            it.copy(
                nameSuggestion = null, autoCorners = null, adjustedCorners = null, cornerSnapSigma = null, canAdjustCorners = false, editor = null,
                cornersRefused = false, restored = false, outline = it.autoOutline ?: it.outline, cutoutUndone = false,
            )
        }
    }

    /** Cancels the picture job and clears the Review's pictures (the capture photo is kept). */
    private fun clearPictures() {
        photoJob?.cancel()
        photoJob = null
        pictures = null
        rectangleBitmap = null
        cutoutBitmap = null
        _state.update {
            it.copy(
                photo = null, photoBusy = false, photoFailed = false,
                removingBackground = false, cutoutStatus = null,
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

        // PLAN v3.1 W8: the Review across process death (SavedStateHandle only; Room is not touched).
        private const val KEY_STEP = "scan_step"
        private const val KEY_OUTLINE = "scan_outline_auto"
        private const val KEY_CORNERS_AUTO = "scan_corners_auto"
        private const val KEY_CORNERS_ADJ = "scan_corners_adjusted"
        private const val KEY_CORNER_SIGMA = "scan_corner_snap_sigma"
        private const val KEY_CAMERA = "scan_camera"
        private const val KEY_TIER = "scan_tier"
        private const val KEY_TIP = "scan_tip"
        private const val KEY_EDITS = "scan_edits"
        private const val KEY_LABEL = "scan_label"
        private const val KEY_HANDS_FREE = "scan_hands_free"
        private const val KEY_CUTOUT_UNDONE = "scan_cutout_undone"
        private const val KEY_PHOTO = "scan_photo_key"

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
