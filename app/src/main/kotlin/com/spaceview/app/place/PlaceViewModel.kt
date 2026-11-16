package com.spaceview.app.place

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spaceview.ar.SurfaceTarget
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.gallery.PlacementModel
import com.spaceview.data.gallery.PlacementResult
import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The outcome of looking up an item's placement model. */
sealed interface PlacementLookup {
    data class Ready(val model: PlacementModel) : PlacementLookup

    /** The item does not exist (deleted). */
    data object Missing : PlacementLookup

    /** Storage or decoding failed; the item may still exist (M4 review MINOR 5: never "deleted"). */
    data object Failed : PlacementLookup
}

/** What Place needs from the gallery (a seam for tests). */
interface PlaceModelSource {
    suspend fun lookup(id: Long): PlacementLookup

    /** Emits whether the item still exists (false once deleted). */
    fun exists(id: Long): Flow<Boolean>
}

/** [PlaceModelSource] over the real gallery. */
class GalleryPlaceSource(private val repo: GalleryRepository) : PlaceModelSource {
    override suspend fun lookup(id: Long): PlacementLookup = when (val r = repo.placementModel(id)) {
        is PlacementResult.Ready -> PlacementLookup.Ready(r.model)
        PlacementResult.Missing -> PlacementLookup.Missing
        PlacementResult.Failed -> PlacementLookup.Failed
    }

    override fun exists(id: Long): Flow<Boolean> = kotlinx.coroutines.flow.flow {
        repo.observe(id).collect { emit(it != null) }
    }
}

/** The model to render, loaded off the main thread. */
class LoadedModel(val model: PlacementModel, val bytes: ByteArray)

/** Place screen state. */
sealed interface PlaceLoad {
    data object Loading : PlaceLoad
    data class Ready(val loaded: LoadedModel) : PlaceLoad

    /** The item was deleted (or never existed): "This item was deleted" → back. */
    data object Deleted : PlaceLoad

    /** The model could not be read or regenerated: error card with Back (the row is untouched). */
    data object Failed : PlaceLoad
}

/**
 * Place in room (M4, §3). Loads the placement model ([PlaceModelSource.modelForPlacement], which regenerates a
 * stale model once and falls back to the measured box for an unusable mesh), and keeps what must survive process
 * death in [SavedStateHandle] (§3.6): the item id, the surface target, the spin, and the last space reading. Anchors
 * cannot survive a restart: after one the screen restores to Searching with the same settings and says so
 * ([restoredAfterDeath]).
 *
 * Never records a completed scan: Place never owes an ad (K2, D7).
 */
class PlaceViewModel(
    private val itemId: Long,
    private val savedState: SavedStateHandle,
    private val source: PlaceModelSource,
    unitsFlow: Flow<UnitPreference>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val units: StateFlow<UnitPreference> = unitsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, UnitPreference.AUTO)

    private val _load = MutableStateFlow<PlaceLoad>(PlaceLoad.Loading)
    val load: StateFlow<PlaceLoad> = _load.asStateFlow()

    /** True when this view model was rebuilt from saved state (the placement itself could not be restored). */
    val restoredAfterDeath: Boolean = savedState.contains(KEY_TARGET)

    private val _target = MutableStateFlow(savedState.get<String>(KEY_TARGET)?.let { n -> SurfaceTarget.entries.firstOrNull { it.name == n } })
    /** The chosen surface (null until the model is known and a default is picked). */
    val target: StateFlow<SurfaceTarget?> = _target.asStateFlow()

    private val _space = MutableStateFlow(readSpace())
    /** The last space measured on this screen, or null. */
    val space: StateFlow<SpaceReading?> = _space.asStateFlow()

    /** Spin about the surface normal (degrees), restored after process death. */
    val spinDeg: Int get() = savedState.get<Int>(KEY_SPIN) ?: 0

    /** The wall height slider's last value (item centre above the floor, m), restored after process death. */
    val centreHeightM: Double? get() = savedState.get<Double>(KEY_HEIGHT)

    /** The space reading came from before a restart (it is only a length, so it is still shown, labelled). */
    private var spaceFromBefore: Boolean = readSpace() != null

    /** The session's pause count when the current space was measured. */
    private var spacePauses = Int.MAX_VALUE

    fun setCentreHeight(heightM: Double) {
        if (heightM.isFinite()) savedState[KEY_HEIGHT] = heightM
    }

    /**
     * True when the space was measured before the latest pause of the AR session ([pausesNow] from
     * `PlacementStatus.pauses`) or before a restart: the card labels it "measured before the pause" (§3.6).
     */
    fun spaceMeasuredBeforePause(pausesNow: Int): Boolean = _space.value != null && (spaceFromBefore || pausesNow > spacePauses)

    init {
        reload()
        viewModelScope.launch {
            // The first value is the current state; later `false` means it was deleted while open.
            source.exists(itemId).drop(1).collect { exists -> if (!exists) _load.value = PlaceLoad.Deleted }
        }
    }

    /** (Re)loads the model, e.g. after an error card's retry. */
    fun reload() {
        _load.value = PlaceLoad.Loading
        viewModelScope.launch {
            _load.value = try {
                when (val r = source.lookup(itemId)) {
                    PlacementLookup.Missing -> PlaceLoad.Deleted
                    PlacementLookup.Failed -> PlaceLoad.Failed
                    is PlacementLookup.Ready -> {
                        val model = r.model
                        val bytes = withContext(io) { model.file.readBytes() }
                        if (_target.value == null) setTarget(defaultTarget(model.origin))
                        PlaceLoad.Ready(LoadedModel(model, bytes))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaceLoad.Failed
            }
        }
    }

    fun setTarget(t: SurfaceTarget) {
        _target.value = t
        savedState[KEY_TARGET] = t.name
    }

    fun setSpinDeg(deg: Int) {
        savedState[KEY_SPIN] = deg
    }

    /** A completed space measurement (a length: it survives a pause and process death), taken at [pausesNow]. */
    fun setSpace(reading: SpaceReading?, pausesNow: Int = 0) {
        _space.value = reading
        spaceFromBefore = false
        spacePauses = pausesNow
        if (reading == null) {
            savedState.remove<Any>(KEY_SPACE)
        } else {
            savedState[KEY_SPACE] = doubleArrayOf(
                reading.kind.ordinal.toDouble(), reading.primaryM, reading.primarySigma,
                reading.secondaryM ?: Double.NaN, reading.secondarySigma ?: Double.NaN, if (reading.primaryHorizontal) 1.0 else 0.0,
            )
        }
    }

    /** The verdict for the current space, or null without one. */
    fun verdict(model: PlacementModel, target: SurfaceTarget, space: SpaceReading?): PlaceFit? {
        space ?: return null
        return PlaceVerdict.evaluate(model.kind, model.width, model.height, model.depth, target.isWall, space)
    }

    private fun readSpace(): SpaceReading? {
        val a = savedState.get<DoubleArray>(KEY_SPACE) ?: return null
        if (a.size != 6) return null
        val kind = SpaceKind.entries.getOrNull(a[0].toInt()) ?: return null
        return SpaceReading(
            kind = kind,
            primaryM = a[1],
            primarySigma = a[2],
            secondaryM = a[3].takeIf { it.isFinite() },
            secondarySigma = a[4].takeIf { it.isFinite() },
            primaryHorizontal = a[5] != 0.0,
        )
    }

    companion object {
        const val KEY_TARGET = "place_target"
        const val KEY_SPIN = "place_spin_deg"
        const val KEY_SPACE = "place_space"
        const val KEY_HEIGHT = "place_centre_height_m"

        /** Wall items (BACK_CENTER: frames, TVs) go on a wall; everything else on the floor. */
        fun defaultTarget(origin: BoxOrigin): SurfaceTarget = if (origin == BoxOrigin.BACK_CENTER) SurfaceTarget.WALL else SurfaceTarget.FLOOR
    }
}
