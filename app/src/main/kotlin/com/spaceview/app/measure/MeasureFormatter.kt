package com.spaceview.app.measure

import com.spaceview.ar.MeasureMode
import com.spaceview.ar.Reading
import com.spaceview.data.history.MeasurementKind
import com.spaceview.engine.ContextFormatted
import com.spaceview.engine.LengthUnit
import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Measurement
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.Units

/**
 * Display text for measurements, always through the engine's precision-honest formatter
 * ([Units]): the value is never shown more precisely than its ±. Pure; unit-tested on the JVM.
 */
object MeasureFormatter {

    /**
     * Context-aware units (UI research §6.4): long spans and heights read as rooms/people
     * (ft-in in AUTO), everything else as objects (cm). Small objects get mm when the ± allows.
     */
    fun contextFor(kind: MeasurementKind, primaryMetres: Double): MeasureContext = when (kind) {
        MeasurementKind.DISTANCE -> when {
            primaryMetres >= ROOM_SPAN_M -> MeasureContext.ROOM
            primaryMetres < SMALL_OBJECT_M -> MeasureContext.SMALL_OBJECT
            else -> MeasureContext.OBJECT
        }
        MeasurementKind.HEIGHT -> if (primaryMetres >= ROOM_HEIGHT_M) MeasureContext.ROOM else MeasureContext.OBJECT
        MeasurementKind.RECTANGLE -> if (primaryMetres < SMALL_OBJECT_M) MeasureContext.SMALL_OBJECT else MeasureContext.OBJECT
    }

    fun kindOf(mode: MeasureMode): MeasurementKind = when (mode) {
        MeasureMode.DISTANCE -> MeasurementKind.DISTANCE
        MeasureMode.HEIGHT -> MeasurementKind.HEIGHT
        MeasureMode.RECTANGLE -> MeasurementKind.RECTANGLE
        // Scan only (never saved to History): formatted like an object rectangle.
        MeasureMode.BOX -> MeasurementKind.RECTANGLE
    }

    fun contextFor(reading: Reading): MeasureContext = contextFor(kindOf(reading.mode), reading.primary.value)

    /** "61.0 cm ± 0.5 (24 in)". */
    fun length(m: Measurement, context: MeasureContext, pref: UnitPreference): ContextFormatted =
        Units.formatForContext(m, context, pref)

    /** Area text with ±, e.g. "0.24 m² ± 0.01". */
    fun area(m: Measurement, context: MeasureContext, pref: UnitPreference): String =
        Units.formatArea(m, Units.defaultAreaUnit(context, pref))

    /** Camera → target distance for the reticle readout ("0.8 m" / "2′ 7″"), deliberately coarse. */
    fun targetDistance(cm: Int, pref: UnitPreference): String {
        val metres = cm / 100.0
        return if (pref == UnitPreference.IMPERIAL) {
            Units.formatValueOnly(metres, Units.METRES_PER_INCH, LengthUnit.FEET_INCHES)
        } else {
            Units.formatValueOnly(metres, 0.1, LengthUnit.M)
        }
    }

    /** Relative scale correction for the calibration card: 1.012 → "+1.2%". */
    fun percent(fraction: Double): String {
        val pct = fraction * 100
        val text = String.format(java.util.Locale.ROOT, "%.1f%%", kotlin.math.abs(pct))
        return when {
            pct > 0.05 -> "+$text"
            pct < -0.05 -> "−$text"
            else -> text
        }
    }

    /** Distances at or above this read as room spans (ft-in in AUTO). */
    const val ROOM_SPAN_M = 1.5

    /** Heights at or above this read as room / person heights. */
    const val ROOM_HEIGHT_M = 1.0

    /** Below this, an object is "small" (mm allowed when ± ≤ 2 mm). */
    const val SMALL_OBJECT_M = 0.15
}
