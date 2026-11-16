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
 * @property modelKind engine `ModelKind` name (v3; legacy rows are `PROXY_BOX`).
 * @property templateId / [templateParams] the furniture template and its parameters as JSON (TEMPLATE only).
 * @property meshExtentW … [meshExtentDBound95] the scanned mesh's measured extents and 95% ± (meshes only, §7.1.5:
 *   percentile spreads, height from the support plane). Shown beside the item's size; never used for it.
 * @property meshGlbW / [meshGlbH] / [meshGlbD] the mesh GLB's own bounding box (its K8 extras), which differs from the
 *   §7.1.5 extents (floor cut, percentile trim): placement checks the GLB against these (M4 review MAJOR 2).
 * @property meshInfo JSON diagnostics of a mesh scan.
 * @property captureMethod `CaptureMethod` name (NULL = before v3).
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
    // ---- v3 (ARCHITECTURE-v2 §9): one consolidated migration for the sprint. ----
    @ColumnInfo(name = "model_kind", defaultValue = "'PROXY_BOX'") val modelKind: String = "PROXY_BOX",
    @ColumnInfo(name = "template_id") val templateId: String? = null,
    @ColumnInfo(name = "template_params") val templateParams: String? = null,
    @ColumnInfo(name = "mesh_extent_w_m") val meshExtentW: Double? = null,
    @ColumnInfo(name = "mesh_extent_h_m") val meshExtentH: Double? = null,
    @ColumnInfo(name = "mesh_extent_d_m") val meshExtentD: Double? = null,
    @ColumnInfo(name = "mesh_extent_w_bound95_m") val meshExtentWBound95: Double? = null,
    @ColumnInfo(name = "mesh_extent_h_bound95_m") val meshExtentHBound95: Double? = null,
    @ColumnInfo(name = "mesh_extent_d_bound95_m") val meshExtentDBound95: Double? = null,
    @ColumnInfo(name = "mesh_glb_w_m") val meshGlbW: Double? = null,
    @ColumnInfo(name = "mesh_glb_h_m") val meshGlbH: Double? = null,
    @ColumnInfo(name = "mesh_glb_d_m") val meshGlbD: Double? = null,
    @ColumnInfo(name = "mesh_info") val meshInfo: String? = null,
    @ColumnInfo(name = "capture_method") val captureMethod: String? = null,
)
