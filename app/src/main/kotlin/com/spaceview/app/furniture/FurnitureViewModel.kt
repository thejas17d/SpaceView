package com.spaceview.app.furniture

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spaceview.app.pro.GatedGalleryStore
import com.spaceview.app.pro.GatedSave
import com.spaceview.app.pro.ProFeature
import com.spaceview.app.scan.ScanSettings
import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.gallery.TemplateDraft
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.GlbWriter
import com.spaceview.engine.Measurement
import com.spaceview.engine.furniture.FurniturePalettes
import com.spaceview.engine.furniture.FurnitureTemplate
import com.spaceview.engine.furniture.ParamError
import com.spaceview.engine.furniture.ParamSpec
import com.spaceview.engine.furniture.Params
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the furniture flow needs from the gallery and the Pro gates (a seam, so the view model is JVM-tested). */
interface FurnitureStore {
    /** The feature that blocks starting [templateId] (a Pro template, or a full Free gallery); null = may start. */
    suspend fun templateBlocker(templateId: String): ProFeature?

    /** Saves through the gates; throws when storage fails. */
    suspend fun save(draft: TemplateDraft): GatedSave

    suspend fun get(id: Long): GalleryItem?
}

/** [FurnitureStore] on the app's single gated store and the gallery. */
class GalleryFurnitureStore(private val gated: GatedGalleryStore, private val gallery: GalleryRepository) : FurnitureStore {
    override suspend fun templateBlocker(templateId: String): ProFeature? = gated.templateBlocker(templateId)
    override suspend fun save(draft: TemplateDraft): GatedSave = gated.saveTemplate(draft)
    override suspend fun get(id: Long): GalleryItem? = gallery.get(id)
}

/** One-off events for the route (navigation). */
sealed interface FurnitureEvent {
    /** Contract K5: open the paywall (from the picker or Review, never from AR). */
    data class OpenPaywall(val feature: ProFeature) : FurnitureEvent
}

/**
 * State of the furniture flow. Holds no ARCore objects.
 *
 * @property checking the template whose Pro / gallery gate is being checked at the picker.
 * @property needsPro Save was refused by a gate (a lapse since the picker, or a full Free gallery); the draft is kept.
 */
data class FurnitureUiState(
    val step: FurnitureStep = FurnitureStep.PICK,
    val inputs: FurnitureInputs? = null,
    val measureIndex: Int = 0,
    val tier: DeviceTier = DeviceTier.NO_DEPTH,
    val label: String? = null,
    val checking: String? = null,
    val saving: Boolean = false,
    val savedId: Long? = null,
    val savedItem: GalleryItem? = null,
    val saveFailed: Boolean = false,
    val needsPro: ProFeature? = null,
    /** Where Back from Review goes: the preview (after measuring) or the options (typed path). */
    val reviewBack: FurnitureStep = FurnitureStep.PREVIEW,
    /** Where Back from the preview goes: the last measure step, or Review when it was opened from there. */
    val previewBack: FurnitureStep = FurnitureStep.MEASURE,
) {
    val template: FurnitureTemplate? get() = FurnitureFlow.template(inputs?.templateId)

    /** The resolved parameters (empty before a template is chosen). */
    val params: Params get() {
        val t = template ?: return emptyMap()
        return FurnitureFlow.params(t, inputs ?: return emptyMap())
    }

    val measureSteps: List<ParamSpec> get() = template?.let { FurnitureFlow.measureSteps(it, params) }.orEmpty()

    /** The parameter being measured on the MEASURE step. */
    val currentSpec: ParamSpec? get() = measureSteps.getOrNull(measureIndex.coerceIn(0, (measureSteps.size - 1).coerceAtLeast(0)))

    val errors: List<ParamError> get() = template?.let { FurnitureFlow.errors(it, params) }.orEmpty()

    val canSave: Boolean get() {
        val t = template ?: return false
        return FurnitureFlow.canSave(t, inputs ?: return false)
    }

    /** "Step n of total" over options (if any), each measure, preview and review. */
    val stepTotal: Int get() = (if (template?.let(FurnitureFlow::hasOptions) == true) 1 else 0) + measureSteps.size + 2

    val stepNumber: Int get() {
        val options = if (template?.let(FurnitureFlow::hasOptions) == true) 1 else 0
        return when (step) {
            FurnitureStep.PICK, FurnitureStep.SAVED -> 0
            FurnitureStep.OPTIONS -> 1
            FurnitureStep.MEASURE -> options + measureIndex + 1
            FurnitureStep.PREVIEW -> options + measureSteps.size + 1
            FurnitureStep.REVIEW -> options + measureSteps.size + 2
        }
    }

    /** Leaving the template would throw away something the user did: ask first. */
    val hasWork: Boolean get() = inputs?.lengths?.isNotEmpty() == true || label != null
}

/**
 * The furniture flow (picker → options → guided measure → preview → review → saved). Manual DI; the model for the
 * preview is built on [cpu]; storage failures are reported, never thrown.
 *
 * Gating (ADR-005, K1/K5): a template is checked when it is picked, **before any AR** (a Pro template, or a full Free
 * gallery, opens the paywall instead), and again at Save through the gated store (the draft is kept on `NeedsPro`).
 *
 * Process death: the step, template, measured and typed values (with their ±), options, preset, palette, name and
 * saved id are kept in [savedState] as plain numbers and strings. A flow that was measuring comes back on the same
 * measure step (a new AR session; values already taken are kept); a saved flow comes back on Saved.
 * [onSaved] is called exactly once per stored item, from the save itself (it counts as a completed scan for the ad cap).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FurnitureViewModel(
    private val savedState: SavedStateHandle,
    settingsFlow: Flow<ScanSettings>,
    private val store: FurnitureStore,
    private val arcoreVersion: () -> String?,
    private val onSaved: () -> Unit,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    val settings: StateFlow<ScanSettings> = settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, ScanSettings())

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<FurnitureUiState> = _state.asStateFlow()

    private val _events = Channel<FurnitureEvent>(Channel.BUFFERED)
    val events: Flow<FurnitureEvent> = _events.receiveAsFlow()

    /** The preview model (GLB bytes) for the current values and palette; null while building or when invalid. */
    val previewGlb: StateFlow<ByteArray?> = _state
        .map { s -> s.template?.let { t -> s.inputs?.let { t to it } } }
        .distinctUntilChanged()
        .mapLatest { pair ->
            val (t, inputs) = pair ?: return@mapLatest null
            withContext(cpu) { buildGlb(t, inputs) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        _state.value.savedId?.let { loadSavedItem(it) }
    }

    private fun restore(): FurnitureUiState {
        val templateId = savedState.get<String>(KEY_TEMPLATE)?.takeIf { FurnitureFlow.template(it) != null }
            ?: return FurnitureUiState()
        val inputs = FurnitureInputs(
            templateId = templateId,
            lengths = FurnitureFlow.decodeLengths(savedState.get<String>(KEY_LENGTHS)),
            options = FurnitureFlow.decodeOptions(savedState.get<String>(KEY_OPTIONS)),
            presetId = savedState.get<String>(KEY_PRESET),
            paletteIndex = savedState.get<Int>(KEY_PALETTE) ?: 0,
        )
        val savedId = savedState.get<Long>(KEY_SAVED_ID)
        val step = stepOf(savedState.get<String>(KEY_STEP)) ?: FurnitureStep.OPTIONS
        val restored = FurnitureUiState(
            step = when {
                savedId != null -> FurnitureStep.SAVED
                step == FurnitureStep.SAVED || step == FurnitureStep.PICK -> FurnitureStep.REVIEW
                else -> step
            },
            inputs = inputs,
            measureIndex = savedState.get<Int>(KEY_INDEX) ?: 0,
            tier = savedState.get<String>(KEY_TIER)?.let { n -> DeviceTier.entries.firstOrNull { it.name == n } } ?: DeviceTier.NO_DEPTH,
            label = savedState.get<String>(KEY_LABEL),
            savedId = savedId,
            reviewBack = stepOf(savedState.get<String>(KEY_REVIEW_BACK)) ?: FurnitureStep.PREVIEW,
            previewBack = stepOf(savedState.get<String>(KEY_PREVIEW_BACK)) ?: FurnitureStep.MEASURE,
        )
        return restored.copy(measureIndex = restored.measureIndex.coerceIn(0, (restored.measureSteps.size - 1).coerceAtLeast(0)))
    }

    private fun stepOf(name: String?): FurnitureStep? = FurnitureStep.entries.firstOrNull { it.name == name }

    private fun setState(transform: (FurnitureUiState) -> FurnitureUiState) {
        _state.update(transform)
        val s = _state.value
        savedState[KEY_STEP] = s.step.name
        savedState[KEY_TEMPLATE] = s.inputs?.templateId
        savedState[KEY_LENGTHS] = s.inputs?.let { FurnitureFlow.encodeLengths(it.lengths) }
        savedState[KEY_OPTIONS] = s.inputs?.let { FurnitureFlow.encodeOptions(it.options) }
        savedState[KEY_PRESET] = s.inputs?.presetId
        savedState[KEY_PALETTE] = s.inputs?.paletteIndex
        savedState[KEY_INDEX] = s.measureIndex
        savedState[KEY_TIER] = s.tier.name
        savedState[KEY_LABEL] = s.label
        savedState[KEY_SAVED_ID] = s.savedId
        savedState[KEY_REVIEW_BACK] = s.reviewBack.name
        savedState[KEY_PREVIEW_BACK] = s.previewBack.name
    }

    private fun updateInputs(transform: (FurnitureInputs) -> FurnitureInputs) {
        setState { s -> s.inputs?.let { s.copy(inputs = transform(it), needsPro = null) } ?: s }
    }

    // ---- picker ----------------------------------------------------------------------------------------------

    /** Picks [templateId]: checks the Pro and gallery gates first (before any AR); opens the paywall when needed. */
    fun pick(templateId: String) {
        val t = FurnitureFlow.template(templateId) ?: return
        if (_state.value.checking != null || _state.value.step != FurnitureStep.PICK) return
        setState { it.copy(checking = templateId) }
        viewModelScope.launch {
            val blocker = try {
                store.templateBlocker(templateId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // never block the user on our own read error; Save checks again
            }
            if (blocker != null) {
                setState { it.copy(checking = null) }
                _events.send(FurnitureEvent.OpenPaywall(blocker))
                return@launch
            }
            setState {
                FurnitureUiState(
                    step = if (FurnitureFlow.hasOptions(t)) FurnitureStep.OPTIONS else FurnitureStep.MEASURE,
                    inputs = FurnitureInputs(templateId),
                )
            }
        }
    }

    /** Back to the picker; everything set for the template is dropped (the route confirms first when [FurnitureUiState.hasWork]). */
    fun backToPicker() {
        if (_state.value.saving) return
        setState { FurnitureUiState() }
    }

    // ---- options ---------------------------------------------------------------------------------------------

    fun choosePreset(presetId: String?) {
        updateInputs { inputs ->
            val preset = FurnitureFlow.template(inputs.templateId)?.presets?.firstOrNull { it.id == presetId }
            // The preset's own options replace earlier picks; its lengths only prefill what was not measured or typed.
            inputs.copy(presetId = preset?.id, options = inputs.options - preset?.values?.keys.orEmpty())
        }
    }

    fun setOption(paramId: String, value: Int) {
        updateInputs { it.copy(options = it.options + (paramId to value)) }
    }

    /** Options → the guided measure. */
    fun startMeasuring() {
        setState { it.copy(step = FurnitureStep.MEASURE, measureIndex = 0) }
    }

    /** Options → Review, typing every size instead of measuring (no camera). */
    fun typeInstead() {
        setState { it.copy(step = FurnitureStep.REVIEW, reviewBack = FurnitureStep.OPTIONS) }
    }

    /**
     * Leaves the AR steps without a camera (permission refused, AR unavailable): to Review when something was already
     * measured or typed (nothing is lost), else back to the options.
     */
    fun leaveMeasure() {
        setState { s ->
            when {
                s.step != FurnitureStep.MEASURE -> s
                s.inputs?.lengths?.isNotEmpty() == true -> s.copy(step = FurnitureStep.REVIEW, reviewBack = FurnitureStep.MEASURE)
                s.template?.let(FurnitureFlow::hasOptions) == true -> s.copy(step = FurnitureStep.OPTIONS)
                else -> FurnitureUiState()
            }
        }
    }

    // ---- guided measure --------------------------------------------------------------------------------------

    /** The AR reading [m] was accepted for [paramId]; moves to the next measure step. */
    fun useMeasured(paramId: String, m: Measurement, tier: DeviceTier) {
        updateInputs { it.copy(lengths = it.lengths + (paramId to FurnitureFlow.measured(m))) }
        setState { it.copy(tier = tier) }
        nextMeasure()
    }

    /** A typed length (an estimate, no ±). On the measure step it moves on. */
    fun typeLength(paramId: String, meters: Double) {
        if (!meters.isFinite() || meters <= 0) return
        updateInputs { it.copy(lengths = it.lengths + (paramId to Dimension.typed(meters))) }
        if (_state.value.step == FurnitureStep.MEASURE && _state.value.currentSpec?.id == paramId) nextMeasure()
    }

    /** Drops what was measured or typed for [paramId]: it goes back to its typical default (W, D, H become missing). */
    fun resetLength(paramId: String) {
        updateInputs { it.copy(lengths = it.lengths - paramId) }
    }

    fun nextMeasure() {
        setState { s ->
            if (s.step != FurnitureStep.MEASURE) return@setState s
            if (s.measureIndex < s.measureSteps.size - 1) {
                s.copy(measureIndex = s.measureIndex + 1)
            } else {
                s.copy(step = FurnitureStep.PREVIEW, previewBack = FurnitureStep.MEASURE)
            }
        }
    }

    fun previousMeasure() {
        setState { s ->
            when {
                s.step != FurnitureStep.MEASURE -> s
                s.measureIndex > 0 -> s.copy(measureIndex = s.measureIndex - 1)
                s.template?.let(FurnitureFlow::hasOptions) == true -> s.copy(step = FurnitureStep.OPTIONS)
                // The first step of a template without options: back to the picker (the route confirms first).
                else -> FurnitureUiState()
            }
        }
    }

    // ---- preview / review ------------------------------------------------------------------------------------

    /** Preview → Review. */
    fun toReview() = setState { s ->
        if (s.step != FurnitureStep.PREVIEW) return@setState s
        s.copy(step = FurnitureStep.REVIEW, reviewBack = if (s.previewBack == FurnitureStep.REVIEW) s.reviewBack else FurnitureStep.PREVIEW)
    }

    /** Review → the 3D preview (Back returns to Review). */
    fun toPreview() = setState { s ->
        if (s.step != FurnitureStep.REVIEW) return@setState s
        s.copy(step = FurnitureStep.PREVIEW, previewBack = FurnitureStep.REVIEW)
    }

    /** Back from Preview or Review, to where the user came from. */
    fun back() = setState { s ->
        when (s.step) {
            FurnitureStep.PREVIEW -> if (s.previewBack == FurnitureStep.REVIEW) {
                s.copy(step = FurnitureStep.REVIEW)
            } else {
                s.copy(step = FurnitureStep.MEASURE, measureIndex = (s.measureSteps.size - 1).coerceAtLeast(0))
            }
            FurnitureStep.REVIEW -> when (s.reviewBack) {
                FurnitureStep.OPTIONS -> s.copy(step = FurnitureStep.OPTIONS)
                FurnitureStep.MEASURE -> s.copy(step = FurnitureStep.MEASURE, measureIndex = (s.measureSteps.size - 1).coerceAtLeast(0))
                else -> s.copy(step = FurnitureStep.PREVIEW, previewBack = FurnitureStep.MEASURE)
            }
            else -> s
        }
    }

    fun setPalette(index: Int) {
        if (index !in 0 until FurniturePalettes.count) return
        updateInputs { it.copy(paletteIndex = index) }
    }

    fun setLabel(label: String) {
        setState { it.copy(label = label.take(GalleryRepository.MAX_LABEL)) }
    }

    /** Saves once through the gates; [suggestedLabel] is the default name. */
    fun save(suggestedLabel: String) {
        val s = _state.value
        val t = s.template ?: return
        val inputs = s.inputs ?: return
        if (s.saving || s.savedId != null || !s.canSave) return
        setState { it.copy(saving = true, saveFailed = false, needsPro = null) }
        viewModelScope.launch {
            val result = try {
                val draft = withContext(cpu) { FurnitureFlow.draft(t, inputs, FurnitureFlow.label(s.label, suggestedLabel), s.tier, arcoreVersion()) }
                store.save(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("Furniture not saved: ${e.message}")
                null
            }
            when (result) {
                null -> setState { it.copy(saving = false, saveFailed = true) }
                // Needs Pro (a lapse since the picker, or the Free gallery is full): everything stays for Upgrade then Save.
                is GatedSave.NeedsPro -> setState { it.copy(saving = false, needsPro = result.feature) }
                is GatedSave.Saved -> {
                    setState { it.copy(saving = false, savedId = result.id, step = FurnitureStep.SAVED) }
                    onSaved()
                    loadSavedItem(result.id)
                }
            }
        }
    }

    /** Review → the paywall for the feature that refused the save. */
    fun upgrade() {
        val f = _state.value.needsPro ?: return
        viewModelScope.launch { _events.send(FurnitureEvent.OpenPaywall(f)) }
    }

    private fun loadSavedItem(id: Long) {
        viewModelScope.launch {
            val item = try { store.get(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            _state.update { it.copy(savedItem = item) }
        }
    }

    private fun buildGlb(t: FurnitureTemplate, inputs: FurnitureInputs): ByteArray? = try {
        val params = FurnitureFlow.params(t, inputs)
        if (t.validate(params).isNotEmpty()) null else GlbWriter.writeParts(t.build(params), FurniturePalettes.of(inputs.paletteIndex))
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun warn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    companion object {
        private const val TAG = "FurnitureViewModel"
        internal const val KEY_STEP = "furniture_step"
        internal const val KEY_TEMPLATE = "furniture_template"
        internal const val KEY_LENGTHS = "furniture_lengths"
        internal const val KEY_OPTIONS = "furniture_options"
        internal const val KEY_PRESET = "furniture_preset"
        internal const val KEY_PALETTE = "furniture_palette"
        internal const val KEY_INDEX = "furniture_measure_index"
        internal const val KEY_TIER = "furniture_tier"
        internal const val KEY_LABEL = "furniture_label"
        internal const val KEY_SAVED_ID = "furniture_saved_id"
        internal const val KEY_REVIEW_BACK = "furniture_review_back"
        internal const val KEY_PREVIEW_BACK = "furniture_preview_back"
    }
}
