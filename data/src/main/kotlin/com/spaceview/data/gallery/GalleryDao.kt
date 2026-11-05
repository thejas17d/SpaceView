package com.spaceview.data.gallery

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** AR Gallery queries. All suspend / Flow: Room runs them off the main thread. */
@Dao
interface GalleryDao {
    @Insert
    suspend fun insert(entity: GalleryItemEntity): Long

    @Query("SELECT * FROM gallery_items ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<GalleryItemEntity>>

    @Query("SELECT * FROM gallery_items WHERE id = :id")
    fun observe(id: Long): Flow<GalleryItemEntity?>

    @Query("SELECT * FROM gallery_items WHERE id = :id")
    suspend fun get(id: Long): GalleryItemEntity?

    @Query(
        "UPDATE gallery_items SET model_path = :modelPath, photo_path = :photoPath, thumbnail_path = :thumbnailPath, " +
            "total_bytes = :totalBytes WHERE id = :id",
    )
    suspend fun setFiles(id: Long, modelPath: String, photoPath: String?, thumbnailPath: String?, totalBytes: Long)

    @Query("UPDATE gallery_items SET label = :label, updated_at = :updatedAt WHERE id = :id")
    suspend fun setLabel(id: Long, label: String?, updatedAt: Long): Int

    /**
     * New dimensions together with the regenerated model file (one statement, so the row and the model it
     * points to always change together).
     */
    @Query(
        "UPDATE gallery_items SET width_m = :widthM, width_bound95_m = :widthB, width_source = :widthS, " +
            "height_m = :heightM, height_bound95_m = :heightB, height_source = :heightS, " +
            "depth_m = :depthM, depth_bound95_m = :depthB, depth_source = :depthS, " +
            "quality = :quality, model_path = :modelPath, total_bytes = :totalBytes, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun setDimensions(
        id: Long,
        widthM: Double, widthB: Double, widthS: String,
        heightM: Double, heightB: Double, heightS: String,
        depthM: Double, depthB: Double, depthS: String,
        quality: String,
        modelPath: String,
        totalBytes: Long,
        updatedAt: Long,
    ): Int

    /** The files every row references (for the start-up sweep). */
    @Query("SELECT id, model_path, photo_path, thumbnail_path FROM gallery_items")
    suspend fun allFileRefs(): List<GalleryFileRefs>

    @Query("DELETE FROM gallery_items WHERE id = :id")
    suspend fun delete(id: Long): Int
}

/** The file columns of one gallery row. */
data class GalleryFileRefs(
    val id: Long,
    @ColumnInfo(name = "model_path") val modelPath: String,
    @ColumnInfo(name = "photo_path") val photoPath: String?,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
)
