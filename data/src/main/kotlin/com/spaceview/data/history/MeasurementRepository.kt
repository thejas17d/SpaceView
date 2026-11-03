package com.spaceview.data.history

import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What was measured. Stored by name; never rename an entry (add new ones instead). */
enum class MeasurementKind { DISTANCE, HEIGHT, RECTANGLE }

/**
 * A history entry as the app sees it.
 *
 * @property primary distance / height / rectangle width.
 * @property secondary rectangle height.
 * @property area rectangle area (m²).
 */
data class SavedMeasurement(
    val id: Long,
    val kind: MeasurementKind,
    val primary: Measurement,
    val secondary: Measurement?,
    val area: Measurement?,
    val quality: Quality,
    val context: MeasureContext,
    val calibrated: Boolean,
    val timestampMillis: Long,
    val label: String?,
    val thumbnailPath: String?,
)

/** History storage. Thin mapping over [MeasurementDao]; all calls are main-safe. */
class MeasurementRepository(private val dao: MeasurementDao) {

    /** Newest first. */
    fun observeHistory(limit: Int = HISTORY_LIMIT): Flow<List<SavedMeasurement>> =
        dao.observeRecent(limit).map { rows -> rows.mapNotNull { it.toDomain() } }

    /** Saves a measurement and returns its id. Older rows beyond [HISTORY_LIMIT] are dropped. */
    suspend fun save(
        kind: MeasurementKind,
        primary: Measurement,
        secondary: Measurement? = null,
        area: Measurement? = null,
        quality: Quality,
        context: MeasureContext,
        calibrated: Boolean,
        timestampMillis: Long,
        label: String? = null,
        thumbnailPath: String? = null,
    ): Long {
        val id = dao.insert(
            MeasurementEntity(
                kind = kind.name,
                valueMetres = primary.value,
                sigmaMetres = primary.sigma,
                secondaryMetres = secondary?.value,
                secondarySigmaMetres = secondary?.sigma,
                areaSquareMetres = area?.value,
                areaSigma = area?.sigma,
                quality = quality.name,
                unitContext = context.name,
                calibrated = calibrated,
                timestampMillis = timestampMillis,
                label = label?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL),
                thumbnailPath = thumbnailPath,
            ),
        )
        dao.trimTo(HISTORY_LIMIT)
        return id
    }

    suspend fun rename(id: Long, label: String?) = dao.setLabel(id, label?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL))

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clearAll() = dao.deleteAll()

    companion object {
        /**
         * Repository on the app's Room database (opened lazily on the first query). Call once per
         * process (AppContainer); Room types stay internal to `:data`.
         */
        fun create(context: android.content.Context): MeasurementRepository =
            MeasurementRepository(com.spaceview.data.SpaceViewDatabase.create(context).measurementDao())

        /** History keeps the newest 200 measurements. */
        const val HISTORY_LIMIT = 200

        const val MAX_LABEL = 80
    }
}

/** Row → domain; rows with unknown enum names or invalid numbers are skipped, never crash. */
internal fun MeasurementEntity.toDomain(): SavedMeasurement? = runCatching {
    SavedMeasurement(
        id = id,
        kind = MeasurementKind.valueOf(kind),
        primary = Measurement(valueMetres, sigmaMetres),
        secondary = if (secondaryMetres != null) Measurement(secondaryMetres, secondarySigmaMetres ?: 0.0) else null,
        area = if (areaSquareMetres != null) Measurement(areaSquareMetres, areaSigma ?: 0.0) else null,
        quality = Quality.valueOf(quality),
        context = MeasureContext.valueOf(unitContext),
        calibrated = calibrated,
        timestampMillis = timestampMillis,
        label = label,
        thumbnailPath = thumbnailPath,
    )
}.getOrNull()
