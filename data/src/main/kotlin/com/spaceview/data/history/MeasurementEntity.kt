package com.spaceview.data.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved measurement (History, screen S13). Values are SI metres (m² for area) as doubles, so
 * nothing is lost to unit rounding; the UI formats them with the engine's precision-honest rules.
 *
 * @property kind [MeasurementKind] name.
 * @property valueMetres distance / height / rectangle width.
 * @property sigmaMetres 1σ of [valueMetres] (the UI shows ±95 = 1.96σ).
 * @property secondaryMetres / [secondarySigmaMetres] rectangle height.
 * @property areaSquareMetres / [areaSigma] rectangle area.
 * @property quality engine `Quality` name (GOOD / FAIR / ROUGH).
 * @property unitContext engine `MeasureContext` name used to pick display units.
 * @property calibrated a scale calibration was applied.
 * @property timestampMillis wall-clock save time (epoch ms).
 */
@Entity(tableName = "measurements", indices = [Index("timestamp")])
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "value_m") val valueMetres: Double,
    @ColumnInfo(name = "sigma_m") val sigmaMetres: Double,
    @ColumnInfo(name = "secondary_m") val secondaryMetres: Double? = null,
    @ColumnInfo(name = "secondary_sigma_m") val secondarySigmaMetres: Double? = null,
    @ColumnInfo(name = "area_m2") val areaSquareMetres: Double? = null,
    @ColumnInfo(name = "area_sigma_m2") val areaSigma: Double? = null,
    val quality: String,
    @ColumnInfo(name = "unit_context") val unitContext: String,
    val calibrated: Boolean = false,
    @ColumnInfo(name = "timestamp") val timestampMillis: Long,
    val label: String? = null,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
)
