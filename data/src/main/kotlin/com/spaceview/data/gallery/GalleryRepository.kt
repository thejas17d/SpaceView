package com.spaceview.data.gallery

import androidx.room.withTransaction
import com.spaceview.data.SpaceViewDatabase
import com.spaceview.data.StorageLayout
import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.GlbWriter
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import com.spaceview.engine.Uncertainty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A saved gallery item as the app sees it.
 *
 * @property quality badge of the measured dimensions (estimates excluded); see [allEstimated].
 * @property measuredWidth / [measuredHeight] / [measuredDepth] the values as measured when the scan was saved
 *   (null for a side that was not measured); edits never change them.
 * @property calibrationFactor scale factor applied to the measured values (1.0 = none, null = unknown).
 * @property modelFile true-size GLB (1 unit = 1 m; FRAME/TV origin at the back-face centre, others at the bottom
 *   centre). Its file name changes on every edit: always use the current item's path.
 * @property photoFile rectified front photo (≤ 1024 px), null when the scan had none.
 * @property thumbnailFile 256 px WebP, null when the scan had no photo.
 * @property bytes total size of the item's files on disk.
 */
data class GalleryItem(
    val id: Long,
    val kind: ObjectKind,
    val width: Dimension,
    val height: Dimension,
    val depth: Dimension,
    val quality: Quality,
    val calibrated: Boolean,
    val deviceTier: DeviceTier,
    val arcoreVersion: String?,
    val label: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val modelFile: File,
    val photoFile: File?,
    val thumbnailFile: File?,
    val bytes: Long,
    val measuredWidth: Dimension? = null,
    val measuredHeight: Dimension? = null,
    val measuredDepth: Dimension? = null,
    val calibrationFactor: Double? = null,
) {
    /** True if any dimension was typed or preset rather than measured. */
    val hasEstimates: Boolean get() = width.isEstimate || height.isEstimate || depth.isEstimate

    /** True if no dimension is measured any more (the badge then means nothing; show "Edited by you"). */
    val allEstimated: Boolean get() = width.isEstimate && height.isEstimate && depth.isEstimate

    /** True if the user typed any dimension (scan Review or gallery edit). */
    val hasTyped: Boolean
        get() = width.source == DimensionSource.TYPED || height.source == DimensionSource.TYPED || depth.source == DimensionSource.TYPED
}

/** Runs a block inside one database transaction (Room on Android, a pass-through in JVM tests). */
interface GalleryTransactions {
    suspend fun <R> run(block: suspend () -> R): R

    /** No transaction: for tests with an in-memory DAO. */
    object None : GalleryTransactions {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }
}

/**
 * AR Gallery storage: Room rows plus, per item, `filesDir/gallery/<id>/` with
 * - `model.glb` (`model-<time>.glb` after an edit): a true-size box (engine [GlbWriter]); front face textured
 *   with the photo, other faces neutral;
 * - `front.jpg`: the rectified front photo, long side ≤ 1024 px, JPEG q82;
 * - `thumb.webp`: 256 px lossy WebP.
 *
 * Budget (PLAN M3 gate): an item is at most [MAX_SCAN_BYTES], checked against the exact bytes written. The photo
 * is stored twice (inside the GLB and as `front.jpg` for display and sharing), so when a very detailed photo
 * would break the budget the JPEG quality, then the size, steps down until it fits (if nothing fits, the item is
 * saved without a photo rather than over budget).
 *
 * Atomicity:
 * - Save: files are written into a hidden staging directory (each file temp + fsync + rename); the row is inserted
 *   and the staging directory renamed to `<id>` inside one transaction, which runs to its end even if the caller
 *   is cancelled. Any failure deletes the staging files and rolls the row back.
 * - Edit: the new model is written under a **new** file name, then the dimensions and the model path change in
 *   one UPDATE, then the old model is deleted. Whatever fails or dies in between, the row points at a model of
 *   its own size; an unreferenced model is swept by [cleanUp].
 * - [cleanUp] removes leftovers of a process death (staging or orphan directories, unreferenced files) and repairs
 *   rows whose files are missing (the model is regenerated from the row).
 *
 * Threading / errors: image encoding and GLB building run on [cpu] (two threads at most, so ARCore's threads are
 * not starved); files and Room on [io]; writes are serialised. [observeAll] / [observe] never throw (null means
 * the gallery could not be read); [get], [rename], [updateDimensions], [delete], [shareCopy] and [totalBytes]
 * report failure through their return value. [save] throws, so the caller can tell the user nothing was saved.
 */
class GalleryRepository(
    private val dao: GalleryDao,
    private val filesDir: File,
    private val codec: GalleryImageCodec,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val transactions: GalleryTransactions = GalleryTransactions.None,
    private val cpu: CoroutineDispatcher = DEFAULT_CPU,
    /** App cache directory; share copies go to `<cacheDir>/share/`. Null disables [shareCopy]. */
    private val cacheDir: File? = null,
    /** fsyncs a directory after a rename into it (no-op on the JVM; `Os.fsync` on Android). */
    private val syncDir: (File) -> Unit = {},
) {
    private val layout = StorageLayout(filesDir)
    private val writeLock = Mutex()

    /** All items, newest first; null when the gallery could not be read (never throws). */
    fun observeAll(): Flow<List<GalleryItem>?> =
        dao.observeAll()
            .map<List<GalleryItemEntity>, List<GalleryItem>?> { rows -> rows.mapNotNull { it.toDomain() } }
            .flowOn(io)
            .catch { e -> if (e is CancellationException) throw e else emit(null) }

    /** One item (null when deleted or unreadable). Never throws. */
    fun observe(id: Long): Flow<GalleryItem?> =
        dao.observe(id)
            .map { it?.toDomain() }
            .flowOn(io)
            .catch { e -> if (e is CancellationException) throw e else emit(null) }

    /** Null when missing or unreadable. */
    suspend fun get(id: Long): GalleryItem? = withContext(io) {
        guardedOrNull { dao.get(id)?.toDomain() }
    }

    /**
     * Saves a scan and returns its id. Throws [IllegalArgumentException] for an invalid draft (non-finite or
     * out-of-range dimensions, a measured side without a ±) and [IOException] (or a database exception) when
     * nothing could be saved; in both cases no files or rows are left behind. An undecodable photo is dropped
     * (flat-colour front).
     *
     * Cancellation is honoured until the files are about to be committed; from then on the save completes (so a
     * row never loses its files half-way), and a cancelled caller simply does not receive the id.
     */
    suspend fun save(draft: ScanDraft): Long {
        val w = normalised(draft.width)
        val h = normalised(draft.height)
        val d = normalised(draft.depth)
        for (dim in listOf(w, h, d)) validate(dim, forSave = true)
        val encoded = withContext(cpu) { encode(draft.kind, w, h, d, draft.label, draft.frontJpeg) }
        return withContext(io) {
            writeLock.withLock {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { commit(draft, w, h, d, encoded) }
            }
        }
    }

    private suspend fun commit(draft: ScanDraft, w: Dimension, h: Dimension, d: Dimension, encoded: Encoded): Long {
        val galleryDir = layout.galleryDir
        if (!galleryDir.isDirectory && !galleryDir.mkdirs()) throw IOException("Cannot create $galleryDir")
        val stage = File(galleryDir, STAGING_PREFIX + UUID.randomUUID())
        val committedDir = AtomicReference<File?>(null)
        try {
            if (!stage.mkdir()) throw IOException("Cannot create $stage")
            writeAtomic(File(stage, MODEL), encoded.glb)
            if (encoded.front != null && encoded.thumbnail != null) {
                writeAtomic(File(stage, PHOTO), encoded.front)
                writeAtomic(File(stage, THUMB), encoded.thumbnail)
            }
            syncDir(stage)
            val bytes = dirBytes(stage)
            check(bytes <= MAX_SCAN_BYTES) { "Item is $bytes B, over the $MAX_SCAN_BYTES B budget" }
            val now = clock()
            val hasPhoto = encoded.front != null
            return transactions.run {
                val id = dao.insert(
                    GalleryItemEntity(
                        kind = draft.kind.name,
                        widthMeters = w.meters, widthBound95 = w.bound95, widthSource = w.source.name,
                        heightMeters = h.meters, heightBound95 = h.bound95, heightSource = h.source.name,
                        depthMeters = d.meters, depthBound95 = d.bound95, depthSource = d.source.name,
                        measuredWidthMeters = w.measuredOrNull()?.meters, measuredWidthBound95 = w.measuredOrNull()?.bound95,
                        measuredHeightMeters = h.measuredOrNull()?.meters, measuredHeightBound95 = h.measuredOrNull()?.bound95,
                        measuredDepthMeters = d.measuredOrNull()?.meters, measuredDepthBound95 = d.measuredOrNull()?.bound95,
                        quality = draft.quality.name,
                        calibrated = draft.calibrated,
                        calibrationFactor = draft.calibrationFactor?.takeIf { it.isFinite() && it > 0 },
                        deviceTier = draft.deviceTier.name,
                        arcoreVersion = draft.arcoreVersion?.take(MAX_VERSION),
                        label = cleanLabel(draft.label),
                        createdAt = now,
                        updatedAt = now,
                        modelPath = "",
                    ),
                )
                val dir = layout.itemDir(id.toString())
                // An id can be handed out again after a rolled-back insert: anything there is an orphan.
                if (dir.exists()) dir.deleteRecursively()
                if (!stage.renameTo(dir)) throw IOException("Cannot move $stage to $dir")
                committedDir.set(dir)
                // The rename must be durable before the row commits, or a power loss could keep the row only.
                syncDir(galleryDir)
                dao.setFiles(
                    id,
                    modelPath = relative(id, MODEL),
                    photoPath = if (hasPhoto) relative(id, PHOTO) else null,
                    thumbnailPath = if (hasPhoto) relative(id, THUMB) else null,
                    totalBytes = bytes,
                )
                id
            }
        } catch (e: Throwable) {
            stage.deleteRecursively()
            committedDir.get()?.deleteRecursively()
            throw e
        }
    }

    /** Renames (blank clears the label). False when the item is missing or the database could not be written. */
    suspend fun rename(id: Long, label: String?): Boolean = withContext(io) {
        guarded { dao.setLabel(id, cleanLabel(label), clock()) > 0 }
    }

    /**
     * Manually edited dimensions (metres; null = unchanged). Each given dimension becomes [DimensionSource.TYPED]
     * (± 0: typed values are shown without a ±) and the GLB is regenerated at the new size. The measured values
     * recorded at save time are kept. The badge can only stay or get worse (an edit never upgrades a measurement).
     * False for invalid values, a missing item or a storage failure; the item is then unchanged.
     */
    suspend fun updateDimensions(id: Long, widthM: Double? = null, heightM: Double? = null, depthM: Double? = null): Boolean {
        val edits = listOf(widthM, heightM, depthM)
        if (edits.any { it != null && !isValidSide(it) }) return false
        return withContext(io) {
            writeLock.withLock {
                guarded {
                    val row = dao.get(id) ?: return@guarded false
                    val item = row.toDomain() ?: return@guarded false
                    val w = widthM?.let { Dimension.typed(it) } ?: item.width
                    val h = heightM?.let { Dimension.typed(it) } ?: item.height
                    val d = depthM?.let { Dimension.typed(it) } ?: item.depth
                    val measured = measuredQuality(w, h, d)
                    val quality = if (measured == null) item.quality else Quality.worst(item.quality, measured)
                    val photo = item.photoFile?.takeIf { it.isFile }
                    val glb = withContext(cpu) { buildGlb(item.kind, w, h, d, photo?.readBytes(), item.label) }
                    val dir = layout.itemDir(id.toString())
                    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
                    val newModel = File(dir, nextModelName(dir, item.modelFile.name))
                    withContext(NonCancellable) {
                        try {
                            writeAtomic(newModel, glb)
                            syncDir(dir)
                            val bytes = newModel.length() + (photo?.length() ?: 0L) + (item.thumbnailFile?.takeIf { it.isFile }?.length() ?: 0L)
                            val updated = dao.setDimensions(
                                id,
                                w.meters, w.bound95, w.source.name,
                                h.meters, h.bound95, h.source.name,
                                d.meters, d.bound95, d.source.name,
                                quality.name,
                                relative(id, newModel.name),
                                bytes,
                                clock(),
                            ) > 0
                            // Only now is the old model unreferenced (a failed delete is swept by cleanUp).
                            if (updated) item.modelFile.takeIf { it != newModel }?.delete() else newModel.delete()
                            updated
                        } catch (e: Throwable) {
                            newModel.delete()
                            throw e
                        }
                    }
                }
            }
        }
    }

    /** Deletes the row, then its files and any share copies. False when the database could not be written. */
    suspend fun delete(id: Long): Boolean = withContext(io) {
        writeLock.withLock {
            guarded {
                dao.delete(id)
                // A failed file delete leaves an orphan directory; cleanUp() removes it later.
                layout.itemDir(id.toString()).deleteRecursively()
                shareDir()?.listFiles()?.filter { it.name.startsWith("$id-") }?.forEach { it.deleteRecursively() }
                true
            }
        }
    }

    /**
     * A copy of the item's front photo for the share sheet: `<cacheDir>/share/<id>-<updatedAt>/<fileName>`, so a
     * receiver that reads lazily keeps a stable file while the item is edited, and a later share never overwrites
     * it. Copies older than a day are pruned; [delete] removes the item's copies. Null without a photo or on error.
     */
    suspend fun shareCopy(id: Long, fileName: String): File? = withContext(io) {
        guardedOrNull {
            require(fileName.isNotBlank() && fileName.none { it == '/' || it == '\\' } && fileName != "." && fileName != "..")
            val base = shareDir() ?: return@guardedOrNull null
            val item = dao.get(id)?.toDomain() ?: return@guardedOrNull null
            val photo = item.photoFile?.takeIf { it.isFile } ?: return@guardedOrNull null
            val now = clock()
            base.listFiles()?.forEach { if (now - it.lastModified() > SHARE_TTL_MS) it.deleteRecursively() }
            val dir = File(base, "$id-${item.updatedAt}")
            if (!dir.isDirectory && !dir.mkdirs()) return@guardedOrNull null
            val out = File(dir, fileName)
            if (!out.isFile || out.length() != photo.length()) photo.copyTo(out, overwrite = true)
            dir.setLastModified(now) // after the copy, which may touch the directory's time
            out
        }
    }

    /** Bytes used by all gallery files (0 if unreadable). */
    suspend fun totalBytes(): Long = withContext(io) {
        guardedOrNull { dirBytes(layout.galleryDir) } ?: 0L
    }

    /**
     * Removes leftovers of an interrupted save, edit or delete (staging directories, item directories without a
     * row, files no row references such as a superseded model or a `*.tmp`) and repairs rows whose files are
     * missing: a missing model is regenerated from the row (it is pure data), a missing photo is dropped from the
     * row, a missing thumbnail is rebuilt from the photo. Call once per process, e.g. when the gallery first opens.
     * Returns the number of directories / files removed plus rows repaired.
     */
    suspend fun cleanUp(): Int = withContext(io) {
        writeLock.withLock {
            guardedOrNull {
                val refs = dao.allFileRefs().associateBy { it.id }
                var fixed = 0
                for (f in layout.galleryDir.listFiles().orEmpty()) {
                    when {
                        f.name.startsWith(STAGING_PREFIX) -> if (f.deleteRecursively()) fixed++
                        f.isDirectory -> {
                            val id = f.name.toLongOrNull() ?: continue
                            val ref = refs[id]
                            if (ref == null) {
                                if (f.deleteRecursively()) fixed++
                            } else {
                                val keep = setOfNotNull(ref.modelPath, ref.photoPath, ref.thumbnailPath)
                                for (file in f.listFiles().orEmpty()) {
                                    if ("gallery/${f.name}/${file.name}" !in keep && file.deleteRecursively()) fixed++
                                }
                            }
                        }
                    }
                }
                for (ref in refs.values) if (repair(ref)) fixed++
                fixed
            } ?: 0
        }
    }

    /** Repairs one row whose files are missing; true if something was changed. */
    private suspend fun repair(ref: GalleryFileRefs): Boolean {
        val model = ref.modelPath.takeIf { it.isNotEmpty() }?.let { File(filesDir, it) }
        val photo = ref.photoPath?.let { File(filesDir, it) }
        val thumb = ref.thumbnailPath?.let { File(filesDir, it) }
        val modelOk = model?.isFile == true
        val photoOk = photo == null || photo.isFile
        val thumbOk = thumb == null || thumb.isFile
        if (modelOk && photoOk && thumbOk) return false
        val row = dao.get(ref.id) ?: return false
        val item = row.toDomain() ?: return false
        val dir = layout.itemDir(ref.id.toString())
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val keptPhoto = photo?.takeIf { it.isFile }
        var keptThumb = thumb?.takeIf { it.isFile && keptPhoto != null }
        if (keptPhoto != null && keptThumb == null) {
            keptThumb = try {
                withContext(cpu) { thumbnailOf(keptPhoto.readBytes()) }?.let { bytes -> File(dir, THUMB).also { writeAtomic(it, bytes) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // the grid then shows the "No photo" placeholder; the photo itself is still there
            }
        }
        if (keptPhoto == null) thumb?.delete()
        var modelFile = model?.takeIf { it.isFile }
        if (modelFile == null) {
            val glb = withContext(cpu) { buildGlb(item.kind, item.width, item.height, item.depth, keptPhoto?.readBytes(), item.label) }
            modelFile = File(dir, nextModelName(dir, model?.name ?: MODEL)).also { writeAtomic(it, glb) }
        }
        syncDir(dir)
        dao.setFiles(
            ref.id,
            modelPath = relative(ref.id, modelFile.name),
            photoPath = keptPhoto?.let { relative(ref.id, it.name) },
            thumbnailPath = keptThumb?.let { relative(ref.id, it.name) },
            totalBytes = modelFile.length() + (keptPhoto?.length() ?: 0L) + (keptThumb?.length() ?: 0L),
        )
        return true
    }

    // ---- internals ------------------------------------------------------------------------------

    /** What a save writes: the GLB and, when the scan had a decodable photo, the front JPEG and thumbnail. */
    internal class Encoded(
        val glb: ByteArray,
        val front: ByteArray?,
        val thumbnail: ByteArray?,
        val frontWidth: Int,
        val frontHeight: Int,
        val frontQuality: Int,
    ) {
        val totalBytes: Long get() = glb.size.toLong() + (front?.size ?: 0) + (thumbnail?.size ?: 0)
    }

    /**
     * Front JPEG (≤ 1024 px, q82, stepping down only to keep the item within budget), the WebP thumbnail and the
     * GLB, sized against the **exact** bytes they will occupy. Without a (decodable) photo: the GLB only.
     */
    internal fun encode(kind: ObjectKind, w: Dimension, h: Dimension, d: Dimension, label: String?, source: ByteArray?): Encoded {
        fun noPhoto() = Encoded(buildGlb(kind, w, h, d, null, label), null, null, 0, 0, 0)
        if (source == null) return noPhoto()
        val decoded = codec.decode(source, FRONT_LONG_SIDES.first()) ?: return noPhoto()
        try {
            val thumb = thumbnail(decoded)
            for (side in FRONT_LONG_SIDES) {
                val img = decoded.scaled(side)
                try {
                    for (q in FRONT_QUALITIES) {
                        val jpeg = img.jpeg(q)
                        val glb = buildGlb(kind, w, h, d, jpeg, label)
                        val out = Encoded(glb, jpeg, thumb, img.width, img.height, q)
                        if (out.totalBytes <= MAX_SCAN_BYTES) return out
                    }
                } finally {
                    if (img !== decoded) img.release()
                }
            }
        } finally {
            decoded.release()
        }
        // Nothing fits (not seen even for pure noise: 512 px at q50 is far below budget): never exceed it.
        return noPhoto()
    }

    private fun thumbnail(decoded: GalleryImage): ByteArray {
        val thumbImage = decoded.scaled(THUMB_LONG_SIDE)
        return try {
            thumbImage.webp(THUMB_QUALITY)
        } finally {
            if (thumbImage !== decoded) thumbImage.release()
        }
    }

    private fun thumbnailOf(jpeg: ByteArray): ByteArray? {
        val decoded = codec.decode(jpeg, THUMB_LONG_SIDE) ?: return null
        return try {
            thumbnail(decoded)
        } finally {
            decoded.release()
        }
    }

    private fun buildGlb(kind: ObjectKind, w: Dimension, h: Dimension, d: Dimension, frontJpeg: ByteArray?, label: String?): ByteArray =
        GlbWriter.writeBox(
            widthM = w.meters,
            heightM = h.meters,
            depthM = d.meters,
            frontJpeg = frontJpeg,
            sideColorSrgb = SIDE_COLOR,
            frontColorSrgb = FRONT_COLOR,
            origin = if (kind == ObjectKind.FRAME || kind == ObjectKind.TV) BoxOrigin.BACK_CENTER else BoxOrigin.BOTTOM_CENTER,
            name = cleanLabel(label) ?: kind.name.lowercase().replaceFirstChar { it.uppercase() },
        )

    /**
     * Row → item. Unknown enum names (a later rename, a downgrade) fall back to the most cautious value instead of
     * hiding the row; only non-finite or out-of-range numbers make a row unreadable (null).
     */
    private fun GalleryItemEntity.toDomain(): GalleryItem? = runCatching {
        GalleryItem(
            id = id,
            kind = enumOr(kind, ObjectKind.OTHER),
            width = Dimension(widthMeters, widthBound95, enumOr(widthSource, DimensionSource.PRESET)),
            height = Dimension(heightMeters, heightBound95, enumOr(heightSource, DimensionSource.PRESET)),
            depth = Dimension(depthMeters, depthBound95, enumOr(depthSource, DimensionSource.PRESET)),
            quality = enumOr(quality, Quality.ROUGH),
            calibrated = calibrated,
            deviceTier = enumOr(deviceTier, DeviceTier.NO_DEPTH),
            arcoreVersion = arcoreVersion,
            label = label,
            createdAt = createdAt,
            updatedAt = updatedAt,
            modelFile = File(filesDir, modelPath.ifEmpty { relative(id, MODEL) }),
            photoFile = photoPath?.let { File(filesDir, it) },
            thumbnailFile = thumbnailPath?.let { File(filesDir, it) },
            bytes = totalBytes,
            measuredWidth = measuredOf(measuredWidthMeters, measuredWidthBound95),
            measuredHeight = measuredOf(measuredHeightMeters, measuredHeightBound95),
            measuredDepth = measuredOf(measuredDepthMeters, measuredDepthBound95),
            calibrationFactor = calibrationFactor,
        ).also { item -> listOf(item.width, item.height, item.depth).forEach { validate(it, forSave = false) } }
    }.getOrNull()

    /** A model file name in [dir] that is neither [current] nor an existing file: `model-<time>[-k].glb`. */
    private fun nextModelName(dir: File, current: String): String {
        val stamp = clock()
        var k = 0
        while (true) {
            val name = if (k == 0) "model-$stamp.glb" else "model-$stamp-$k.glb"
            if (name != current && !File(dir, name).exists()) return name
            k++
        }
    }

    private fun shareDir(): File? = cacheDir?.let { File(it, SHARE_DIR) }

    private suspend fun guarded(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun <T> guardedOrNull(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** PLAN M3 gate: a saved scan is at most 0.5 MB (decimal, the stricter reading). */
        const val MAX_SCAN_BYTES = 500_000L

        /** Front photo long sides tried in order (the first is the normal case). */
        internal val FRONT_LONG_SIDES = intArrayOf(1024, 768, 512)

        /** JPEG qualities tried in order (82 is the normal case). */
        internal val FRONT_QUALITIES = intArrayOf(82, 74, 66, 58, 50)

        const val THUMB_LONG_SIDE = 256
        internal const val THUMB_QUALITY = 75

        /** Neutral warm grey for the sides/back/top of the box, and the front when there is no photo. */
        internal const val SIDE_COLOR = 0x9A9893
        internal const val FRONT_COLOR = 0xD8D5CC

        /** Smallest side accepted (1 mm); the largest is [GlbWriter.MAX_SIDE_M]. */
        const val MIN_SIDE_M = 0.001

        const val MAX_LABEL = 80
        private const val MAX_VERSION = 40

        const val MODEL = "model.glb"
        const val PHOTO = "front.jpg"
        const val THUMB = "thumb.webp"
        private const val STAGING_PREFIX = ".tmp-"
        private const val SHARE_DIR = "share"

        /** Share copies older than this are pruned on the next share. */
        internal const val SHARE_TTL_MS = 24L * 60 * 60 * 1000

        /**
         * Encoding and GLB building: CPU-bound, capped at two threads so a save running next to a live ARCore
         * session does not starve its tracking threads (android-performance.md).
         */
        private val DEFAULT_CPU: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

        fun isValidSide(m: Double): Boolean = m.isFinite() && m >= MIN_SIDE_M && m <= GlbWriter.MAX_SIDE_M

        /**
         * [forSave]: a measured side must carry a positive ± (every measured number shows its tolerance). Stored
         * rows are only checked for finite, in-range numbers.
         */
        private fun validate(d: Dimension, forSave: Boolean) {
            require(isValidSide(d.meters)) { "Dimension must be in [$MIN_SIDE_M, ${GlbWriter.MAX_SIDE_M}] m, got ${d.meters}" }
            require(d.bound95.isFinite() && d.bound95 >= 0) { "bound95 must be finite and >= 0, got ${d.bound95}" }
            if (forSave && !d.isEstimate) require(d.bound95 > 0) { "A measured dimension needs its ± (bound95 > 0)" }
        }

        /** Values that were not measured carry no ±. */
        private fun normalised(d: Dimension) = if (d.isEstimate && d.bound95 != 0.0) d.copy(bound95 = 0.0) else d

        private fun Dimension.measuredOrNull(): Dimension? = takeIf { it.source == DimensionSource.MEASURED }

        private fun measuredOf(m: Double?, b: Double?): Dimension? =
            if (m != null && b != null && isValidSide(m) && b.isFinite() && b >= 0) Dimension.measured(m, b) else null

        private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback

        /** Worst badge of the measured dimensions, null when all are estimates (as engine BoxMeasurement). */
        internal fun measuredQuality(vararg dims: Dimension): Quality? {
            val q = dims.filter { !it.isEstimate }.map { Quality.of(Measurement(it.meters, it.bound95 / Uncertainty.K95)) }
            return if (q.isEmpty()) null else Quality.worst(*q.toTypedArray())
        }

        private fun cleanLabel(label: String?): String? = label?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL)

        private fun relative(id: Long, file: String) = "gallery/$id/$file"

        private fun dirBytes(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        /** Writes [bytes] to a temp file, fsyncs, then renames it over [target]. */
        internal fun writeAtomic(target: File, bytes: ByteArray) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                // rename(2) replaces atomically on Android; the delete fallback is only for hosts (Windows
                // test JVMs) where rename refuses to replace an existing file.
                if (!tmp.renameTo(target) && !(target.delete() && tmp.renameTo(target))) {
                    throw IOException("Cannot write $target")
                }
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }

        /** Best-effort fsync of a directory, so a rename into it survives a power loss. */
        private fun fsyncDirectory(dir: File) {
            try {
                val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
                try {
                    android.system.Os.fsync(fd)
                } finally {
                    android.system.Os.close(fd)
                }
            } catch (e: android.system.ErrnoException) {
                // Some file systems refuse fsync on a directory; the data itself was already synced.
            }
        }

        /**
         * Repository on the app's Room database (opened lazily) and `filesDir`. Call once per
         * process (AppContainer); Room types stay internal to `:data`.
         */
        fun create(context: android.content.Context): GalleryRepository {
            val app = context.applicationContext
            val db = SpaceViewDatabase.get(app)
            return GalleryRepository(
                dao = db.galleryDao(),
                filesDir = app.filesDir,
                codec = AndroidGalleryImageCodec,
                transactions = RoomGalleryTransactions(db),
                cacheDir = app.cacheDir,
                syncDir = ::fsyncDirectory,
            )
        }
    }
}

/** Room-backed [GalleryTransactions]. */
private class RoomGalleryTransactions(private val db: SpaceViewDatabase) : GalleryTransactions {
    override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction { block() }
}
