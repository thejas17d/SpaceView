package com.spaceview.app.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spaceview.app.settings.SettingsRepository
import com.spaceview.ar.CalibrationCheck
import com.spaceview.ar.Reading
import com.spaceview.data.history.MeasurementRepository
import com.spaceview.data.settings.CalibrationRecord
import com.spaceview.data.settings.MeasureSettingsRepository
import com.spaceview.engine.CalibrationResult
import com.spaceview.engine.LengthUnit
import com.spaceview.engine.ReferenceObject
import com.spaceview.engine.ScaleCalibration
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.Units
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Settings the measure screen needs; [loaded] is false until DataStore answered. */
data class MeasureUiSettings(
    val units: UnitPreference = UnitPreference.AUTO,
    val soundsEnabled: Boolean = true,
    val depthEnabled: Boolean = false,
    val calibration: CalibrationRecord? = null,
    val safetyNoticeShown: Boolean = true,
    val loaded: Boolean = false,
)

/** Why a calibration attempt was refused (each maps to one user message). */
enum class CalibrationRejection { OUT_OF_RANGE, INCONSISTENT, TOO_NOISY, SKEWED, INVALID, NOT_ONE_SURFACE }

/** Outcome of measuring a calibration reference (a tape-measured span, or a card / sheet). */
sealed interface CalibrationOutcome {
    /** [reference] is null for a known (tape-measured) length. */
    data class Accepted(val calibration: ScaleCalibration, val reference: ReferenceObject?) : CalibrationOutcome
    data class Rejected(val reason: CalibrationRejection) : CalibrationOutcome
}

/** The tape-measured length typed on the known-length card. */
sealed interface KnownLengthEntry {
    /** [metres] ± [sigmaMetres] (tape plus the resolution of what was typed). */
    data class Valid(val metres: Double, val sigmaMetres: Double) : KnownLengthEntry

    /** Not a number. */
    data object Invalid : KnownLengthEntry

    /** Outside [MeasureViewModel.KNOWN_LENGTH_MIN_M]..[MeasureViewModel.KNOWN_LENGTH_MAX_M]. */
    data object OutOfRange : KnownLengthEntry
}

/** One-off results for the screen. */
sealed interface MeasureVmEvent {
    data object Saved : MeasureVmEvent
    data object CalibrationApplied : MeasureVmEvent

    /** Room / DataStore could not write (disk full, I/O error). Nothing was saved. */
    data object StorageFailed : MeasureVmEvent
}

/**
 * Persistence side of the measure screen: settings, saving to History, and calibration. It holds
 * no ARCore objects (those live in the screen-scoped `MeasureSession`), so it is safe to keep
 * across configuration changes. Storage failures never crash the app: they are reported as
 * [MeasureVmEvent.StorageFailed] (M2 review minor 9).
 */
class MeasureViewModel(
    private val measureSettings: MeasureSettingsRepository,
    appSettings: SettingsRepository,
    private val history: MeasurementRepository,
    private val clock: () -> Long,
) : ViewModel() {

    val settings: StateFlow<MeasureUiSettings> = combine(
        measureSettings.settings,
        appSettings.unitPreference,
        appSettings.soundsEnabled,
    ) { m, units, sounds ->
        MeasureUiSettings(units, sounds, m.depthEnabled, m.calibration, m.safetyNoticeShown, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MeasureUiSettings())

    private val _events = MutableSharedFlow<MeasureVmEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MeasureVmEvent> = _events.asSharedFlow()

    /** The calibration to apply in ARCore session [sessionId] (null = none; see [calibrationFor]). */
    fun calibrationFor(record: CalibrationRecord?, sessionId: String?): ScaleCalibration? =
        calibrationFor(record, sessionId, clock())

    /**
     * Saves exactly the [reading] the user saw on the result card (M2 review minor 1); a live or
     * missing reading is never saved.
     */
    fun save(reading: Reading, label: String?) {
        if (reading.isLive) return
        viewModelScope.launch {
            val ok = guarded {
                history.save(
                    kind = MeasureFormatter.kindOf(reading.mode),
                    primary = reading.primary,
                    secondary = reading.secondary,
                    area = reading.area,
                    quality = reading.quality,
                    context = MeasureFormatter.contextFor(reading),
                    calibrated = reading.calibrated,
                    timestampMillis = clock(),
                    label = label,
                )
            }
            _events.emit(if (ok) MeasureVmEvent.Saved else MeasureVmEvent.StorageFailed)
        }
    }

    /** Stores an accepted calibration for ARCore session [sessionId] (the only session that may use it). */
    fun applyCalibration(outcome: CalibrationOutcome.Accepted, sessionId: String?) {
        val id = sessionId ?: return
        val record = CalibrationRecord.from(outcome.calibration, outcome.reference, clock(), id) ?: return
        viewModelScope.launch {
            val ok = guarded { measureSettings.setCalibration(record) }
            _events.emit(if (ok) MeasureVmEvent.CalibrationApplied else MeasureVmEvent.StorageFailed)
        }
    }

    fun dismissSafetyNotice() {
        viewModelScope.launch {
            if (!guarded { measureSettings.markSafetyNoticeShown() }) _events.emit(MeasureVmEvent.StorageFailed)
        }
    }

    /** Runs a storage call; false when it threw (cancellation is rethrown). */
    private suspend fun guarded(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    companion object {
        /**
         * Card / A4 calibration in the UI. Off: with the tier's 1 cm absolute floor in every length
         * (M2 review B1) a card or A4 sheet can never pin the scale better than the prior, so the
         * engine rejects it every time. The engine paths stay; re-enable this once the bench test
         * (docs/PLAN.md §7) justifies a smaller floor and the small references are accepted again.
         */
        const val CALIBRATION_SMALL_REFERENCES_ENABLED = false

        /** A known length must be at least this long (shorter spans cannot beat the scale prior). */
        const val KNOWN_LENGTH_MIN_M = 1.0

        /** … and at most this long (one straight span the user can aim at end to end). */
        const val KNOWN_LENGTH_MAX_M = 5.0

        /** 1σ of a tape reading itself (placeholder), before the resolution of what was typed. */
        const val TAPE_SIGMA_M = 0.001

        /** Unit the known length is typed in: inches when the user prefers imperial, else centimetres. */
        fun knownLengthUnit(pref: UnitPreference): LengthUnit =
            if (pref == UnitPreference.IMPERIAL) LengthUnit.INCH_DECIMAL else LengthUnit.CM

        /**
         * Parses the typed tape length in [unit] ("200", "200.5", "78,7"). The σ is the tape's own
         * [TAPE_SIGMA_M] combined with the typed resolution (a uniform ±½ of the last digit, /√12), so
         * "200" cm honestly carries more doubt than "200.4". Pure.
         */
        fun parseKnownLength(text: String, unit: LengthUnit): KnownLengthEntry {
            val t = text.trim().replace(',', '.')
            if (t.isEmpty() || t.count { it == '.' } > 1 || !t.all { it.isDigit() || it == '.' } || !t.any { it.isDigit() }) {
                return KnownLengthEntry.Invalid
            }
            val value = t.toDoubleOrNull() ?: return KnownLengthEntry.Invalid
            val metres = Units.toMetres(value, unit)
            if (!metres.isFinite() || metres < KNOWN_LENGTH_MIN_M - 1e-9 || metres > KNOWN_LENGTH_MAX_M + 1e-9) return KnownLengthEntry.OutOfRange
            val decimals = t.substringAfter('.', "").length
            val resolution = Units.toMetres(Math.pow(10.0, -decimals.toDouble()), unit)
            val readSigma = resolution / sqrt(12.0)
            return KnownLengthEntry.Valid(metres, sqrt(TAPE_SIGMA_M * TAPE_SIGMA_M + readSigma * readSigma))
        }

        /** Corners more than this far from 90° make a calibration untrustworthy. */
        const val MAX_CALIBRATION_SKEW_DEG = 4.0

        /**
         * The stored calibration is applied **only inside the ARCore session that made it** and only
         * while it is not stale (M2 review M3). A calibration from an earlier session is not applied at
         * all: VIO scale errors of two sessions are independent, so applying an old factor would add
         * error while the ± shrank. Pure.
         */
        fun calibrationFor(record: CalibrationRecord?, sessionId: String?, nowMillis: Long): ScaleCalibration? =
            record?.applicableIn(sessionId, nowMillis)

        /** Maps the engine's calibration check of a measured reference to a UI outcome (pure). */
        fun evaluateCalibration(ref: ReferenceObject?, check: CalibrationCheck?): CalibrationOutcome {
            check ?: return CalibrationOutcome.Rejected(CalibrationRejection.INVALID)
            if (!check.onOneSurface) return CalibrationOutcome.Rejected(CalibrationRejection.NOT_ONE_SURFACE)
            if (!check.geometricallyConsistent || check.maxRightAngleDeviationDeg > MAX_CALIBRATION_SKEW_DEG) {
                return CalibrationOutcome.Rejected(CalibrationRejection.SKEWED)
            }
            return when (val result = check.result) {
                is CalibrationResult.Accepted -> CalibrationOutcome.Accepted(result.calibration, ref)
                is CalibrationResult.Rejected -> CalibrationOutcome.Rejected(
                    when (result.reason) {
                        CalibrationResult.Reason.FACTOR_OUT_OF_RANGE -> CalibrationRejection.OUT_OF_RANGE
                        CalibrationResult.Reason.INCONSISTENT -> CalibrationRejection.INCONSISTENT
                        CalibrationResult.Reason.TOO_NOISY -> CalibrationRejection.TOO_NOISY
                        CalibrationResult.Reason.INVALID_INPUT -> CalibrationRejection.INVALID
                    },
                )
            }
        }
    }
}
