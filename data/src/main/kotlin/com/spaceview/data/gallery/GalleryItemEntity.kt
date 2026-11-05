package com.spaceview.data.gallery

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved AR Gallery item (screens S8/S9). Dimensions are SI metres as doubles with their 95% ±
 * (bound95, metres; the History table stores 1σ instead), so nothing is lost to unit rounding.
 *
 * Files live in app-private `filesDir/gallery/<id>/`; the paths below are **relative to filesDir**
 * (e.g. `gallery/12/model.glb`) so they survive the app being moved to other storage.
 *
 * @property kind [ObjectKind] name.
 * @property quality engine `Quality` name of the measured (non-estimate) dimensions.
 * @property deviceTier engine `DeviceTier` name of the phone that measured it.
 * @property arcoreVersion ARCore (Google Play Services for AR) version name, if known.
 * @property widthSource / [heightSource] / [depthSource] [DimensionSource] names (MEASURED / PRESET / TYPED).
 * @property measuredWidthMeters / [measuredWidthBound95] … the values as measured at save time (null for a side
 *   that was not measured). Never overwritten by edits, so "Reset to measured" stays possible.
 * @property calibrationFactor scale factor applied to the measured values (1.0 = none, null = unknown).
 * @property modelPath the true-size GLB (versioned file name, e.g. `gallery/12/model-1730800000000.glb`);
 *   [photoPath] / [thumbnailPath] are null when the scan had no photo.
 * @property totalBytes bytes on disk of all of the item's files.
 */
@Entity(tableName = "gallery_items", indices = [Index("created_at")])
data class GalleryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "width_m") val widthMeters: Double,
    @ColumnInfo(name = "width_bound95_m") val widthBound95: Double,
    @ColumnInfo(name = "width_source") val widthSource: String,
    @ColumnInfo(name = "height_m") val heightMeters: Double,
    @ColumnInfo(name = "height_bound95_m") val heightBound95: Double,
    @ColumnInfo(name = "height_source") val heightSource: String,
    @ColumnInfo(name = "depth_m") val depthMeters: Double,
    @ColumnInfo(name = "depth_bound95_m") val depthBound95: Double,
    @ColumnInfo(name = "depth_source") val depthSource: String,
    @ColumnInfo(name = "measured_width_m") val measuredWidthMeters: Double? = null,
    @ColumnInfo(name = "measured_width_bound95_m") val measuredWidthBound95: Double? = null,
    @ColumnInfo(name = "measured_height_m") val measuredHeightMeters: Double? = null,
    @ColumnInfo(name = "measured_height_bound95_m") val measuredHeightBound95: Double? = null,
    @ColumnInfo(name = "measured_depth_m") val measuredDepthMeters: Double? = null,
    @ColumnInfo(name = "measured_depth_bound95_m") val measuredDepthBound95: Double? = null,
    val quality: String,
    val calibrated: Boolean,
    @ColumnInfo(name = "calibration_factor") val calibrationFactor: Double? = null,
    @ColumnInfo(name = "device_tier") val deviceTier: String,
    @ColumnInfo(name = "arcore_version") val arcoreVersion: String? = null,
    val label: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "model_path") val modelPath: String,
    @ColumnInfo(name = "photo_path") val photoPath: String? = null,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long = 0,
)
