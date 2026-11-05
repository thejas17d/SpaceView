package com.spaceview.app.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spaceview.app.settings.SettingsRepository
import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.DimensionSource
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.engine.ContextFormatted
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One grid card, formatted off the composition path. */
data class GalleryCard(
    val item: GalleryItem,
    val dimsText: String,
    /** TVs: diagonal ("43.0 in"; "109 cm (43.0 in)" with the metric preference); null otherwise. */
    val diagonalText: String?,
    /** Largest ± of the measured dimensions ("± 1 cm"); null when every dimension is an estimate. */
    val plusMinusText: String?,
)

/** [failed]: the gallery could not be read (shown as an error, never as "Nothing saved yet"). */
data class GalleryUiState(val cards: List<GalleryCard> = emptyList(), val loaded: Boolean = false, val failed: Boolean = false)

/** Gallery grid (S8). */
class GalleryViewModel(
    private val repository: GalleryRepository,
    appSettings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<GalleryUiState> = combine(repository.observeAll(), appSettings.unitPreference) { items, units ->
        if (items == null) GalleryUiState(loaded = true, failed = true) else GalleryUiState(items.map { toCard(it, units) }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    init {
        // Once per process: remove leftovers of an interrupted save/delete.
        if (cleanedUp.compareAndSet(false, true)) viewModelScope.launch { repository.cleanUp() }
    }

    companion object {
        private val cleanedUp = AtomicBoolean(false)

        fun toCard(item: GalleryItem, units: UnitPreference) = GalleryCard(
            item = item,
            dimsText = GalleryFormatter.compactDims(item, units),
            diagonalText = GalleryFormatter.diagonalShort(item, units),
            plusMinusText = GalleryFormatter.maxPlusMinus(item, units),
        )
    }
}

/**
 * One spec-block row: W / H / D / diagonal. [isEstimate]: not measured (no ±); [isTyped]: typed by the user
 * (for the diagonal: rests on a typed side), otherwise a preset.
 */
data class DimensionRow(val which: Which, val formatted: ContextFormatted, val isEstimate: Boolean, val isTyped: Boolean = false) {
    enum class Which { WIDTH, HEIGHT, DEPTH, DIAGONAL }
}

data class GalleryItemUiState(
    val loaded: Boolean = false,
    /** Null once loaded means the item is gone (deleted elsewhere or unreadable). */
    val item: GalleryItem? = null,
    val rows: List<DimensionRow> = emptyList(),
    val plusMinusText: String? = null,
    val shareDims: String = "",
    val units: UnitPreference = UnitPreference.AUTO,
)

/** Item detail (S9): edit, rename, delete, share. */
class GalleryItemViewModel(
    private val id: Long,
    private val repository: GalleryRepository,
    appSettings: SettingsRepository,
) : ViewModel() {

    /** One-shot problems shown as a snackbar. */
    enum class Problem { SAVE_FAILED, DELETE_FAILED, SHARE_FAILED }

    val state: StateFlow<GalleryItemUiState> = combine(repository.observe(id), appSettings.unitPreference) { item, units ->
        toState(item, units)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryItemUiState())

    private val _problem = MutableStateFlow<Problem?>(null)
    val problem: StateFlow<Problem?> = _problem.asStateFlow()

    fun problemShown() {
        _problem.value = null
    }

    fun reportShareFailed() {
        _problem.value = Problem.SHARE_FAILED
    }

    fun rename(label: String) {
        viewModelScope.launch { if (!repository.rename(id, label)) _problem.value = Problem.SAVE_FAILED }
    }

    /** Metres; null = unchanged. */
    fun updateDimensions(widthM: Double?, heightM: Double?, depthM: Double?) {
        if (widthM == null && heightM == null && depthM == null) return
        viewModelScope.launch {
            if (!repository.updateDimensions(id, widthM, heightM, depthM)) _problem.value = Problem.SAVE_FAILED
        }
    }

    /** A share copy of the front photo (null without a photo or on error). */
    suspend fun shareCopy(fileName: String): File? = repository.shareCopy(id, fileName)

    /**
     * Deletes, then calls [onDeleted] (on the main thread) only if it worked. The caller must ignore a callback that
     * arrives after the user has already left the screen.
     */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (repository.delete(id)) onDeleted() else _problem.value = Problem.DELETE_FAILED
        }
    }

    companion object {
        fun toState(item: GalleryItem?, units: UnitPreference): GalleryItemUiState {
            if (item == null) return GalleryItemUiState(loaded = true, units = units)
            val rows = buildList {
                fun row(which: DimensionRow.Which, d: Dimension) =
                    DimensionRow(which, GalleryFormatter.dimension(d, item.kind, units), d.isEstimate, d.source == DimensionSource.TYPED)
                add(row(DimensionRow.Which.WIDTH, item.width))
                add(row(DimensionRow.Which.HEIGHT, item.height))
                add(row(DimensionRow.Which.DEPTH, item.depth))
                GalleryFormatter.diagonalDetail(item, units)?.let {
                    val typed = item.width.source == DimensionSource.TYPED || item.height.source == DimensionSource.TYPED
                    add(DimensionRow(DimensionRow.Which.DIAGONAL, it, GalleryFormatter.diagonal(item).second, typed))
                }
            }
            return GalleryItemUiState(
                loaded = true,
                item = item,
                rows = rows,
                plusMinusText = GalleryFormatter.maxPlusMinus(item, units),
                shareDims = GalleryFormatter.shareDims(item, units),
                units = units,
            )
        }
    }
}
