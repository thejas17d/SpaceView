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
import com.spaceview.engine.ReferenceObject
import com.spaceview.engine.ScaleCalibration
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.UncertaintyModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
enum class CalibrationRejection { OUT_OF_RANGE, INCONSISTENT, TOO_NOISY, SKEWED, INVALID }

/** Outcome of measuring a reference card / sheet. */
sealed interface CalibrationOutcome {
    data class Accepted(val calibration: ScaleCalibration, val reference: ReferenceObject) : CalibrationOutcome
    data class Rejected(val reason: CalibrationRejection) : CalibrationOutcome
}

/** One-off results for the screen. */
sealed interface MeasureVmEvent {
    data object Saved : MeasureVmEvent
    data object CalibrationApplied : MeasureVmEvent
}

/**
 * Persistence side of the measure screen: settings, saving to History, and calibration. It holds
 * no ARCore objects (those live in the screen-scoped `MeasureSession`), so it is safe to keep
 * across configuration changes.
 */
class MeasureViewModel(
    private val measureSettings: MeasureSettingsRepository,
    appSettings: SettingsRepository,
    private val history: MeasurementRepository,
    private val clock: () -> Long,
) : ViewModel() {

    /** Start of this measuring visit: calibrations made since then belong to the same AR session. */
    val visitStartedAtMillis: Long = clock()

    val settings: StateFlow<MeasureUiSettings> = combine(
        measureSettings.settings,
        appSettings.unitPreference,
        appSettings.soundsEnabled,
    ) { m, units, sounds ->
        MeasureUiSettings(units, sounds, m.depthEnabled, m.calibration, m.safetyNoticeShown, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MeasureUiSettings())

    private val _events = MutableSharedFlow<MeasureVmEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MeasureVmEvent> = _events.asSharedFlow()

    /** Calibration to apply plus the residual scale floor (larger for a calibration from an earlier session). */
    fun calibrationForSession(record: CalibrationRecord?): Pair<ScaleCalibration?, Double> =
        calibrationForSession(record, visitStartedAtMillis)

    fun save(reading: Reading, label: String?) {
        if (reading.isLive) return
        viewModelScope.launch {
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
            _events.emit(MeasureVmEvent.Saved)
        }
    }

    fun applyCalibration(outcome: CalibrationOutcome.Accepted) {
        // The visit start doubles as the AR session id: plane and anchor are valid only within it.
        val record = CalibrationRecord.from(outcome.calibration, outcome.reference, clock(), visitStartedAtMillis) ?: return
        viewModelScope.launch {
            measureSettings.setCalibration(record)
            _events.emit(MeasureVmEvent.CalibrationApplied)
        }
    }

    fun dismissSafetyNotice() {
        viewModelScope.launch { measureSettings.markSafetyNoticeShown() }
    }

    companion object {
        /**
         * Residual relative scale σ for a calibration from an earlier AR session. VIO scale can
         * differ between sessions; whether a stored calibration carries over must be verified in the
         * bench test, so we stay conservative (placeholder, see docs/PLAN.md §7).
         */
        const val PREVIOUS_SESSION_FLOOR_REL = 0.008

        /** Corners more than this far from 90° make a calibration untrustworthy. */
        const val MAX_CALIBRATION_SKEW_DEG = 4.0

        /**
         * Same AR session ([sessionId] matches): full plane + range scope and the normal floor.
         * Earlier session: the world frame has changed, so only the range scope applies, with a larger floor.
         */
        fun calibrationForSession(record: CalibrationRecord?, sessionId: Long): Pair<ScaleCalibration?, Double> {
            if (record == null) return null to UncertaintyModel.DEFAULT_CALIBRATED_FLOOR_REL
            val same = record.sessionId == sessionId
            val floor = if (same) UncertaintyModel.DEFAULT_CALIBRATED_FLOOR_REL else PREVIOUS_SESSION_FLOOR_REL
            return record.toScaleCalibration(same) to floor
        }

        /** Maps the engine's calibration check of a measured reference to a UI outcome (pure). */
        fun evaluateCalibration(ref: ReferenceObject, check: CalibrationCheck?): CalibrationOutcome {
            check ?: return CalibrationOutcome.Rejected(CalibrationRejection.INVALID)
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
