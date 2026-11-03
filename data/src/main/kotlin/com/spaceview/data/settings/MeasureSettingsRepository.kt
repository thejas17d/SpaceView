package com.spaceview.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.spaceview.engine.Calibrator
import com.spaceview.engine.Plane
import com.spaceview.engine.ReferenceObject
import com.spaceview.engine.ScaleCalibration
import com.spaceview.engine.Vec3
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * A stored scale calibration with its full scope (the engine applies a factor only on the
 * reference's plane and at a similar camera distance).
 *
 * It is used **only inside the ARCore session that produced it** (M2 review M3): VIO scale errors of
 * two sessions are independent, so an old factor would make the reading worse while tightening its
 * ±, and the plane / anchor are world coordinates of that session. Outside it the record is kept
 * only so the UI can say when the user last calibrated.
 *
 * @property calibratedAtMillis wall-clock time it was measured (epoch ms).
 * @property planeNormal / [planeOffset] the reference's plane (n·p + d = 0) in that AR session's world frame.
 * @property anchor the reference's centroid on that plane.
 * @property rangeM camera-to-reference distance at calibration.
 * @property sessionId random id of the ARCore session it was made in (new for every session
 *   SceneView creates, including retries and activity recreation).
 */
data class CalibrationRecord(
    val factor: Double,
    val sigma: Double,
    val reference: ReferenceObject?,
    val calibratedAtMillis: Long,
    val planeNormal: Vec3,
    val planeOffset: Double,
    val anchor: Vec3,
    val rangeM: Double,
    val sessionId: String,
) {
    /** As an engine calibration with its full plane, anchor and range scope. */
    fun toScaleCalibration(): ScaleCalibration =
        ScaleCalibration(factor, sigma, reference = reference, plane = Plane(planeNormal, planeOffset), anchor = anchor, rangeM = rangeM)

    /** True once the record is older than [MAX_AGE_MILLIS] (or dated in the future: the clock changed). */
    fun isStale(nowMillis: Long): Boolean = nowMillis - calibratedAtMillis !in 0..MAX_AGE_MILLIS

    /**
     * The calibration to apply in the ARCore session [currentSessionId] at [nowMillis], or null: only
     * the session that made it may use it, and never once it is stale.
     */
    fun applicableIn(currentSessionId: String?, nowMillis: Long): ScaleCalibration? =
        if (currentSessionId != null && currentSessionId == sessionId && !isStale(nowMillis)) toScaleCalibration() else null

    companion object {
        /** A calibration is stale after 30 days. */
        const val MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        /** Record from an accepted engine calibration; null when its scope (plane, anchor, range) is incomplete. */
        fun from(c: ScaleCalibration, reference: ReferenceObject?, atMillis: Long, sessionId: String): CalibrationRecord? {
            val plane = c.plane ?: return null
            val anchor = c.anchor ?: plane.point
            val range = c.rangeM ?: return null
            return CalibrationRecord(c.factor, c.sigma, reference, atMillis, plane.normal, plane.d, anchor, range, sessionId)
        }
    }
}

/**
 * Measuring settings. (Unit system, sounds and onboarding live in the app's `SettingsRepository`
 * on its own file; this store holds only what the measuring engine needs.)
 *
 * @property depthEnabled use the ARCore Depth API where supported (off by default: costs power).
 * @property safetyNoticeShown the one-time "watch your surroundings" notice was shown.
 */
data class MeasureSettings(
    val calibration: CalibrationRecord? = null,
    val depthEnabled: Boolean = false,
    val safetyNoticeShown: Boolean = false,
)

/**
 * Preferences DataStore for [MeasureSettings], file [FILE_NAME]. Create exactly one instance per
 * process (DataStore forbids two instances on one file).
 */
class MeasureSettingsRepository(private val store: DataStore<Preferences>) {

    val settings: Flow<MeasureSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    /**
     * Stores [record], replacing any earlier one. Throws [IllegalArgumentException] for an implausible
     * record and [IOException] when the file cannot be written (callers catch and report it).
     */
    suspend fun setCalibration(record: CalibrationRecord) {
        require(record.factor > Calibrator.MIN_FACTOR && record.factor < Calibrator.MAX_FACTOR && record.sigma >= 0 && record.sigma.isFinite())
        require(record.rangeM > 0 && record.rangeM.isFinite() && record.planeNormal.isFinite && record.anchor.isFinite && record.planeOffset.isFinite())
        require(record.sessionId.isNotBlank())
        store.edit {
            it[CAL_FACTOR] = record.factor
            it[CAL_SIGMA] = record.sigma
            it[CAL_AT] = record.calibratedAtMillis
            if (record.reference != null) it[CAL_REF] = record.reference.name else it.remove(CAL_REF)
            it[CAL_RANGE] = record.rangeM
            it[CAL_SCOPE] = encode(record.planeNormal, record.planeOffset, record.anchor)
            it[CAL_SESSION] = record.sessionId
            it.remove(LEGACY_CAL_SESSION)
        }
    }

    /** Removes the stored calibration (Settings: "Clear calibration"). Throws [IOException] on a write failure. */
    suspend fun clearCalibration() {
        store.edit { clearCalibrationKeys(it) }
    }

    suspend fun setDepthEnabled(enabled: Boolean) {
        store.edit { it[DEPTH] = enabled }
    }

    suspend fun markSafetyNoticeShown() {
        store.edit { it[SAFETY_SHOWN] = true }
    }

    companion object {
        /** Distinct from the app settings file (`spaceview_settings`) and ad counters (`spaceview_ads`). */
        const val FILE_NAME = "spaceview_measure"

        private val CAL_FACTOR = doublePreferencesKey("calibration_factor")
        private val CAL_SIGMA = doublePreferencesKey("calibration_sigma")
        private val CAL_AT = longPreferencesKey("calibration_at")
        private val CAL_REF = stringPreferencesKey("calibration_reference")
        private val CAL_RANGE = doublePreferencesKey("calibration_range_m")
        /** Plane normal (3), offset, anchor (3) as 7 comma-separated doubles (Locale.ROOT). */
        private val CAL_SCOPE = stringPreferencesKey("calibration_scope")
        /** ARCore session id (a UUID string). */
        private val CAL_SESSION = stringPreferencesKey("calibration_session_id")

        /** M2 builds stored the visit time here; such records are never applied (wrong session identity). */
        private val LEGACY_CAL_SESSION = longPreferencesKey("calibration_session")

        /** Removes every calibration key, including the M2 legacy session key. */
        internal fun clearCalibrationKeys(p: MutablePreferences) {
            p.remove(CAL_FACTOR); p.remove(CAL_SIGMA); p.remove(CAL_AT); p.remove(CAL_REF); p.remove(CAL_RANGE)
            p.remove(CAL_SCOPE); p.remove(CAL_SESSION); p.remove(LEGACY_CAL_SESSION)
        }

        private fun encode(n: Vec3, d: Double, a: Vec3): String =
            doubleArrayOf(n.x, n.y, n.z, d, a.x, a.y, a.z).joinToString(",") { it.toString() }

        /** Parses [encode]'s output; null when malformed (the record is then treated as invalid). */
        internal fun decode(s: String?): Triple<Vec3, Double, Vec3>? {
            val v = s?.split(',')?.map { it.toDoubleOrNull() ?: return null } ?: return null
            if (v.size != 7 || v.any { !it.isFinite() }) return null
            val n = Vec3(v[0], v[1], v[2])
            if (n.length < 0.5) return null
            return Triple(n, v[3], Vec3(v[4], v[5], v[6]))
        }
        private val DEPTH = booleanPreferencesKey("depth_enabled")
        private val SAFETY_SHOWN = booleanPreferencesKey("safety_notice_shown")

        /** Creates the repository on its file. Call once per process (AppContainer). */
        fun create(context: Context): MeasureSettingsRepository = MeasureSettingsRepository(
            PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
        )

        internal fun Preferences.toSettings(): MeasureSettings {
            val factor = this[CAL_FACTOR]
            val sigma = this[CAL_SIGMA]
            val at = this[CAL_AT]
            // A calibration without its full scope (plane, anchor, range, session) is invalid.
            val scope = decode(this[CAL_SCOPE])
            val range = this[CAL_RANGE]
            val session = this[CAL_SESSION]?.takeIf { it.isNotBlank() }
            val calibration = if (
                factor != null && sigma != null && at != null && scope != null && range != null && session != null &&
                factor.isFinite() && sigma.isFinite() && sigma >= 0 && range > 0 && range.isFinite() &&
                factor > Calibrator.MIN_FACTOR && factor < Calibrator.MAX_FACTOR
            ) {
                val ref = this[CAL_REF]?.let { n -> ReferenceObject.entries.firstOrNull { it.name == n } }
                CalibrationRecord(factor, sigma, ref, at, scope.first, scope.second, scope.third, range, session)
            } else {
                null
            }
            return MeasureSettings(
                calibration = calibration,
                depthEnabled = this[DEPTH] ?: false,
                safetyNoticeShown = this[SAFETY_SHOWN] ?: false,
            )
        }
    }
}
