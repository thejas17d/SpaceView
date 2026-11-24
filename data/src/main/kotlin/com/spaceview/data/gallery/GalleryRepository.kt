package com.spaceview.data.gallery

import androidx.room.withTransaction
import com.spaceview.data.SpaceViewDatabase
import com.spaceview.data.StorageLayout
import com.spaceview.engine.ArgbImage
import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.CutoutPixels
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.GLB_VERSION
import com.spaceview.engine.GlbInfo
import com.spaceview.engine.GlbReader
import com.spaceview.engine.GlbWriter
import com.spaceview.engine.Measurement
import com.spaceview.engine.ModelKind
import com.spaceview.engine.PngEncoder
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
import kotlin.math.abs

/**
 * A saved gallery item as the app sees it.
 *
 * @property quality badge of the measured dimensions (estimates excluded); see [allEstimated].
 * @property measuredWidth / [measuredHeight] / [measuredDepth] the values as measured when the scan was saved
 *   (null for a side that was not measured); edits never change them.
 * @property calibrationFactor scale factor applied to the measured values (1.0 = none, null = unknown).
 * @property modelFile true-size GLB (1 unit = 1 m). A cutout is a flat quad centred on its origin; a legacy proxy box
 *   has its origin at the back-face centre (FRAME/TV) or the bottom centre. Its file name changes on every edit:
 *   always use the current item's path.
 * @property photoFile the cutout picture (`cutout.webp`, ≤ 1024 px, transparent background when it was removed) or, for
 *   a legacy proxy box, the rectified front photo (`front.jpg`); null when the scan had none.
 * @property thumbnailFile 256 px WebP (transparent background for a cutout), null when the scan had no photo.
 * @property bytes total size of the item's files on disk.
 * @property modelKind where the model's geometry came from (v3; legacy rows are PROXY_BOX).
 * @property templateId the furniture template of a legacy TEMPLATE row (earlier test builds; v1 makes none).
 * @property meshExtents the scanned mesh's §7.1.5 measured extents and ± (meshes only); never the item's size.
 * @property glbExtents the mesh GLB's own bounding box (meshes only; null for rows saved before it was stored).
 * @property meshInfo JSON diagnostics of a mesh scan.
 * @property captureMethod how the measured sides were captured (null = unknown or before v3).
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
    val modelKind: ModelKind = ModelKind.PROXY_BOX,
    val templateId: String? = null,
    val meshExtents: MeshExtents? = null,
    val glbExtents: GlbExtents? = null,
    val meshInfo: String? = null,
    val captureMethod: CaptureMethod? = null,
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
 * - `model.glb` (`model-<time>.glb` after an edit or a regeneration): the true-size model (engine [GlbWriter]). v1
 *   scans are **cutouts** (PLAN v3 W2, `GlbWriter.writeCutout`): a flat, unlit, double-sided quad at the measured
 *   W × H showing the object's picture, with the background transparent when it was removed. Legacy rows keep their
 *   model: a proxy box, or a furniture template / 3D-scan mesh saved by earlier test builds (placed from the stored
 *   GLB; never regenerated in v1);
 * - `cutout.webp` (cutouts): the picture, long side ≤ 1024 px, lossy WebP with alpha; `front.jpg` (legacy proxy box):
 *   the rectified front photo, JPEG;
 * - `thumb.webp`: 256 px lossy WebP (with alpha for a cutout).
 *
 * Budget (PLAN M3 gate): an item is at most [MAX_SCAN_BYTES], checked against the exact bytes written. The picture
 * is stored twice (inside the GLB and as a file for display and sharing), so when it would break the budget the
 * quality, then the size, steps down until it fits (if nothing fits, the item is saved without a photo rather than
 * over budget). A transparent cutout's texture is a straight-alpha PNG (glTF has no WebP in its core), whose long
 * side steps down through [CUTOUT_TEXTURE_SIDES] first.
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

    /**
     * Number of saved items, for the Free gallery cap (Builder B, P1 review MAJOR 3). Throws if the gallery
     * can't be read; the caller decides (the Pro gate lets the save through rather than block on our error).
     */
    suspend fun count(): Int = withContext(io) { dao.count() }

    /** Live item count for the "18 / 20" counter; null when unreadable. Never throws. */
    fun observeCount(): Flow<Int?> =
        dao.observeCount()
            .map<Int, Int?> { it }
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
        val cutout = draft.cutout
        val modelKind = if (cutout != null) ModelKind.CUTOUT else ModelKind.PROXY_BOX
        val encoded = withContext(cpu) {
            if (cutout != null) {
                encodeCutout(w, h, displayName(draft.kind, draft.label), cutout.image)
            } else {
                encode(draft.kind, w, h, d, draft.label, draft.frontJpeg)
            }
        }
        val photoName = if (cutout != null) CUTOUT_PHOTO else PHOTO
        val staged = Staged(encoded.glb, encoded.front?.takeIf { encoded.thumbnail != null }, encoded.thumbnail?.takeIf { encoded.front != null }, photoName)
        return commitLocked(staged) { now ->
            baseRow(draft.kind, w, h, d, draft.quality, draft.calibrated, draft.calibrationFactor, draft.deviceTier, draft.arcoreVersion, draft.label, now)
                .copy(modelKind = modelKind.name, captureMethod = draft.captureMethod?.name)
        }
    }

    /** The files of a new item: the model always, a photo and a thumbnail when present. */
    private class Staged(val model: ByteArray, val photo: ByteArray?, val thumbnail: ByteArray?, val photoName: String = PHOTO)

    private fun baseRow(
        kind: ObjectKind, w: Dimension, h: Dimension, d: Dimension, quality: Quality, calibrated: Boolean,
        calibrationFactor: Double?, deviceTier: DeviceTier, arcoreVersion: String?, label: String?, now: Long,
    ) = GalleryItemEntity(
        kind = kind.name,
        widthMeters = w.meters, widthBound95 = w.bound95, widthSource = w.source.name,
        heightMeters = h.meters, heightBound95 = h.bound95, heightSource = h.source.name,
        depthMeters = d.meters, depthBound95 = d.bound95, depthSource = d.source.name,
        measuredWidthMeters = w.measuredOrNull()?.meters, measuredWidthBound95 = w.measuredOrNull()?.bound95,
        measuredHeightMeters = h.measuredOrNull()?.meters, measuredHeightBound95 = h.measuredOrNull()?.bound95,
        measuredDepthMeters = d.measuredOrNull()?.meters, measuredDepthBound95 = d.measuredOrNull()?.bound95,
        quality = quality.name,
        calibrated = calibrated,
        calibrationFactor = calibrationFactor?.takeIf { it.isFinite() && it > 0 },
        deviceTier = deviceTier.name,
        arcoreVersion = arcoreVersion?.take(MAX_VERSION),
        label = cleanLabel(label),
        createdAt = now,
        updatedAt = now,
        modelPath = "",
    )

    /** Cancellation is honoured until the files are about to be committed; from then on the save completes. */
    private suspend fun commitLocked(staged: Staged, row: (now: Long) -> GalleryItemEntity): Long = withContext(io) {
        writeLock.withLock {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { commit(staged, row) }
        }
    }

    private suspend fun commit(staged: Staged, row: (now: Long) -> GalleryItemEntity): Long {
        val galleryDir = layout.galleryDir
        if (!galleryDir.isDirectory && !galleryDir.mkdirs()) throw IOException("Cannot create $galleryDir")
        val stage = File(galleryDir, STAGING_PREFIX + UUID.randomUUID())
        val committedDir = AtomicReference<File?>(null)
        try {
            if (!stage.mkdir()) throw IOException("Cannot create $stage")
            writeAtomic(File(stage, MODEL), staged.model)
            staged.photo?.let { writeAtomic(File(stage, staged.photoName), it) }
            staged.thumbnail?.let { writeAtomic(File(stage, THUMB), it) }
            syncDir(stage)
            val bytes = dirBytes(stage)
            check(bytes <= MAX_SCAN_BYTES) { "Item is $bytes B, over the $MAX_SCAN_BYTES B budget" }
            val now = clock()
            return transactions.run {
                val id = dao.insert(row(now))
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
                    photoPath = if (staged.photo != null) relative(id, staged.photoName) else null,
                    thumbnailPath = if (staged.thumbnail != null) relative(id, THUMB) else null,
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
     *
     * By model kind: a cutout or a proxy box is regenerated at the new size; a legacy **mesh** keeps its model (it
     * describes the scanned shape, never the item's size, ADR-013) and only the row changes; a legacy **template** is
     * not editable (its size is a template parameter and v1 has no template library), so this returns false.
     */
    suspend fun updateDimensions(id: Long, widthM: Double? = null, heightM: Double? = null, depthM: Double? = null): Boolean {
        val edits = listOf(widthM, heightM, depthM)
        if (edits.any { it != null && !isValidSide(it) }) return false
        return withContext(io) {
            writeLock.withLock {
                guarded {
                    val row = dao.get(id) ?: return@guarded false
                    val item = row.toDomain() ?: return@guarded false
                    if (item.modelKind == ModelKind.TEMPLATE) return@guarded false
                    val w = widthM?.let { Dimension.typed(it) } ?: item.width
                    val h = heightM?.let { Dimension.typed(it) } ?: item.height
                    val d = depthM?.let { Dimension.typed(it) } ?: item.depth
                    val measured = measuredQuality(w, h, d)
                    val quality = if (measured == null) item.quality else Quality.worst(item.quality, measured)
                    if (!item.modelKind.regenerable) {
                        return@guarded dao.setDimensions(
                            id,
                            w.meters, w.bound95, w.source.name,
                            h.meters, h.bound95, h.source.name,
                            d.meters, d.bound95, d.source.name,
                            quality.name,
                            row.modelPath,
                            row.totalBytes,
                            clock(),
                        ) > 0
                    }
                    val photo = item.photoFile?.takeIf { it.isFile }
                    val thumbBytes = item.thumbnailFile?.takeIf { it.isFile }?.length() ?: 0L
                    val glb = withContext(cpu) { rebuild(item, w, h, d, photo, thumbBytes) }
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
     * Settings → Delete my data (M5): deletes every row, then the whole gallery directory (models, photos,
     * thumbnails, staging leftovers), the share copies and the Place fallback boxes in the cache. Runs under the
     * write lock, so it never interleaves with a save, an edit or [cleanUp]. False when the database could not be
     * written or a directory could not be removed (the caller says so; a retry finishes the job).
     */
    suspend fun deleteAll(): Boolean = withContext(io) {
        writeLock.withLock {
            guarded {
                dao.deleteAll()
                val dirs = listOfNotNull(layout.galleryDir, shareDir(), File(cacheDir ?: File(filesDir, ".cache"), PLACE_DIR))
                // deleteRecursively() is true when the directory is gone afterwards (also when it never existed).
                dirs.map { it.deleteRecursively() }.all { it }
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
        // Only a missing mesh: nothing can be repaired (placement shows the measured box instead).
        if (!modelOk && !item.modelKind.regenerable && photoOk && thumbOk) return false
        val dir = layout.itemDir(ref.id.toString())
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val keptPhoto = photo?.takeIf { it.isFile }
        // A scan's (or a cutout's) thumbnail is made from its photo; a legacy 3D scan's came from a keyframe, without one.
        val thumbFromPhoto = item.modelKind.regenerable
        var keptThumb = thumb?.takeIf { it.isFile && (keptPhoto != null || !thumbFromPhoto) }
        if (keptPhoto != null && keptThumb == null) {
            keptThumb = try {
                withContext(cpu) { thumbnailOf(keptPhoto.readBytes()) }?.let { bytes -> File(dir, THUMB).also { writeAtomic(it, bytes) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // the grid then shows the "No photo" placeholder; the photo itself is still there
            }
        }
        if (keptPhoto == null && thumbFromPhoto) thumb?.delete()
        var modelFile = model?.takeIf { it.isFile }
        if (modelFile == null) {
            // A mesh or a template is never regenerated (ADR-014, PLAN v3): placement falls back to the row's box.
            val glb = if (item.modelKind.regenerable) withContext(cpu) { regenerate(item, keptPhoto, keptThumb?.length() ?: 0L) } else null
            modelFile = glb?.let { bytes -> File(dir, nextModelName(dir, model?.name ?: MODEL)).also { writeAtomic(it, bytes) } }
        }
        syncDir(dir)
        dao.setFiles(
            ref.id,
            modelPath = modelFile?.let { relative(ref.id, it.name) } ?: ref.modelPath,
            photoPath = keptPhoto?.let { relative(ref.id, it.name) },
            thumbnailPath = keptThumb?.let { relative(ref.id, it.name) },
            totalBytes = (modelFile?.length() ?: 0L) + (keptPhoto?.length() ?: 0L) + (keptThumb?.length() ?: 0L),
        )
        return true
    }

    // ---- placement (M4, contract K4) ---------------------------------------------------------------

    /**
     * The model Place should render for item [id] (K4, K8), or null when the item is missing or unreadable.
     * Reads only the GLB's header and JSON chunk (~3 KB).
     * - **Cutout / proxy box:** the stored model is used when its extras match the row (a cutout: W × H; a box:
     *   W × H × D) to 0.1 mm, it was written for the expected origin, and `glbVersion >= GLB_VERSION`. Otherwise (a
     *   stale M3 model, a mismatch, a missing file) it is regenerated once through the versioned-file path: new file,
     *   one UPDATE, then the old file is deleted (NonCancellable, under the write lock).
     * - **Legacy template / mesh** (earlier test builds, PLAN v3 W1): never regenerated and never rescaled. The stored
     *   GLB is placed when it is readable and matches (a template: the row's W × H × D; a mesh: the `mesh_glb_*`
     *   columns); otherwise a proxy box of the row's size is written to the cache ([PlacementModel.meshFallback]) and
     *   the row is not touched. Nothing here fails because a template can't be rebuilt.
     * Runs on [io]; regeneration CPU work on [cpu]. Never throws (null on storage failure).
     */
    suspend fun modelForPlacement(id: Long): PlacementModel? = (placementModel(id) as? PlacementResult.Ready)?.model

    /**
     * As [modelForPlacement], but tells a missing item ([PlacementResult.Missing]) from a storage or decoding failure
     * ([PlacementResult.Failed]: an unreadable row, a full disk during regeneration…), so Place never says "deleted"
     * for an item that still exists (M4 review MINOR 5). Never throws except [CancellationException].
     */
    suspend fun placementModel(id: Long): PlacementResult = withContext(io) {
        try {
            val row = dao.get(id) ?: return@withContext PlacementResult.Missing
            val item = row.toDomain() ?: return@withContext PlacementResult.Failed
            val info = readInfo(item.modelFile)
            val model = when {
                usable(item, info) -> placement(item, item.modelFile, info!!, fallback = false)
                item.modelKind.regenerable -> regenerateForPlacement(id)
                else -> fallbackBox(item)
            }
            when {
                model != null -> PlacementResult.Ready(model)
                dao.get(id) == null -> PlacementResult.Missing // deleted meanwhile
                else -> PlacementResult.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlacementResult.Failed
        }
    }

    private fun readInfo(file: File): GlbInfo? =
        file.takeIf { it.isFile }?.let { f -> runCatching { f.inputStream().use { GlbReader.readInfo(it) } }.getOrNull() }

    /** True when [info] is a current, matching model for [item]. */
    private fun usable(item: GalleryItem, info: GlbInfo?): Boolean {
        if (info == null) return false
        return when (item.modelKind) {
            ModelKind.CUTOUT ->
                info.glbVersion >= GLB_VERSION && info.origin == BoxOrigin.CENTER && info.modelKind == ModelKind.CUTOUT &&
                    abs(info.widthM - item.width.meters) <= PLACEMENT_MATCH_M && abs(info.heightM - item.height.meters) <= PLACEMENT_MATCH_M
            ModelKind.PROXY_BOX, ModelKind.TEMPLATE ->
                info.glbVersion >= GLB_VERSION && info.origin == originOf(item) &&
                    (info.modelKind ?: ModelKind.PROXY_BOX) == item.modelKind &&
                    matches(info, item.width.meters, item.height.meters, item.depth.meters)
            ModelKind.MESH_DEVICE, ModelKind.MESH_CLOUD -> {
                val g = item.glbExtents
                info.modelKind == item.modelKind && info.origin != null &&
                    listOf(info.widthM, info.heightM, info.depthM).all { it > 0 && it <= GlbWriter.MAX_SIDE_M } &&
                    (g == null || matches(info, g.widthM, g.heightM, g.depthM))
            }
        }
    }

    private suspend fun regenerateForPlacement(id: Long): PlacementModel? = writeLock.withLock {
        // Re-read under the lock: another caller may have regenerated it meanwhile.
        val row = dao.get(id) ?: return@withLock null
        val item = row.toDomain() ?: return@withLock null
        readInfo(item.modelFile)?.let { if (usable(item, it)) return@withLock placement(item, item.modelFile, it, fallback = false) }
        val photo = item.photoFile?.takeIf { it.isFile }
        val thumbBytes = item.thumbnailFile?.takeIf { it.isFile }?.length() ?: 0L
        val glb = withContext(cpu) { regenerate(item, photo, thumbBytes) }
            ?: return@withLock fallbackBox(item) // nothing to rebuild from: show the row's box, row untouched
        val dir = layout.itemDir(id.toString())
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val newModel = File(dir, nextModelName(dir, item.modelFile.name))
        withContext(NonCancellable) {
            try {
                writeAtomic(newModel, glb)
                syncDir(dir)
                val thumb = item.thumbnailFile?.takeIf { it.isFile }
                val bytes = newModel.length() + (photo?.length() ?: 0L) + (thumb?.length() ?: 0L)
                // One statement: the row moves to the new model (photo and thumbnail unchanged).
                dao.setFiles(id, relative(id, newModel.name), row.photoPath, row.thumbnailPath, bytes)
                // Only now is the old model unreferenced (a failed delete is swept by cleanUp).
                item.modelFile.takeIf { it != newModel }?.delete()
            } catch (e: Throwable) {
                newModel.delete()
                throw e
            }
        }
        val info = GlbReader.readInfo(glb) ?: return@withLock null
        placement(item, newModel, info, fallback = false)
    }

    /** A box of the item's row size in `<cacheDir>/place/` (never under the item's directory, never in the row). */
    private suspend fun fallbackBox(item: GalleryItem): PlacementModel? {
        val glb = withContext(cpu) { buildGlb(item.kind, item.width, item.height, item.depth, null, item.label) }
        val dir = File(cacheDir ?: File(filesDir, ".cache"), PLACE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, "${item.id}-box.glb")
        writeAtomic(file, glb)
        val info = GlbReader.readInfo(glb) ?: return null
        return placement(item, file, info, fallback = true)
    }

    private fun placement(item: GalleryItem, file: File, info: GlbInfo, fallback: Boolean) = PlacementModel(
        id = item.id,
        file = file,
        widthM = info.widthM,
        heightM = info.heightM,
        depthM = info.depthM,
        origin = info.origin ?: originOf(item),
        kind = item.kind,
        width = item.width,
        height = item.height,
        depth = item.depth,
        modelKind = item.modelKind,
        meshFallback = fallback,
        label = item.label,
        centre = info.centre,
    )

    /** A fresh model of the row's size for a regenerable item, or null for one that can't be rebuilt (template, mesh). */
    private fun regenerate(item: GalleryItem, photo: File?, thumbBytes: Long): ByteArray? =
        if (item.modelKind.regenerable) rebuild(item, item.width, item.height, item.depth, photo, thumbBytes) else null

    /** A regenerable item's model at W × H × D: a cutout from its stored picture, a proxy box from its front photo. */
    private fun rebuild(item: GalleryItem, w: Dimension, h: Dimension, d: Dimension, photo: File?, thumbBytes: Long): ByteArray =
        if (item.modelKind == ModelKind.CUTOUT) {
            val bytes = photo?.readBytes()
            val budget = MAX_SCAN_BYTES - (bytes?.size ?: 0) - thumbBytes
            cutoutFromPhoto(w, h, displayName(item.kind, item.label), bytes, budget)
        } else {
            buildGlb(item.kind, w, h, d, photo?.readBytes(), item.label)
        }

    // ---- cutouts (PLAN v3 W2) -----------------------------------------------------------------------

    /**
     * The files of a new cutout: the picture as `cutout.webp` (≤ 1024 px, keeps alpha), the 256 px thumbnail and the
     * GLB. An opaque picture gets a JPEG texture at the picture's size and quality; a transparent one a straight-alpha
     * PNG with bled edges (no halo) whose long side steps down through [CUTOUT_TEXTURE_SIDES] before the picture's size
     * and quality do. Everything is sized against the **exact** bytes; if nothing fits, a plain panel without a photo.
     */
    internal fun encodeCutout(w: Dimension, h: Dimension, name: String, image: ArgbImage?): Encoded {
        fun plain() = Encoded(GlbWriter.writeCutout(w.meters, h.meters, null, colorSrgb = FRONT_COLOR, name = name), null, null, 0, 0, 0)
        if (image == null) return plain()
        val thumbImage = codec.fromPixels(CutoutPixels.downscale(image, THUMB_LONG_SIDE))
        val thumb = try {
            thumbImage.webp(THUMB_QUALITY)
        } finally {
            thumbImage.release()
        }
        val pictures = HashMap<Int, GalleryImage>()
        fun picture(side: Int) = pictures.getOrPut(side) { codec.fromPixels(CutoutPixels.downscale(image, side)) }
        try {
            if (image.isOpaque) {
                for (side in FRONT_LONG_SIDES) for (q in FRONT_QUALITIES) {
                    val img = picture(side)
                    val glb = GlbWriter.writeCutout(w.meters, h.meters, img.jpeg(q), name = name)
                    val out = Encoded(glb, img.webp(q), thumb, img.width, img.height, q)
                    if (out.totalBytes <= MAX_SCAN_BYTES) return out
                }
            } else {
                val photos = HashMap<Pair<Int, Int>, ByteArray>()
                for (texSide in CUTOUT_TEXTURE_SIDES) {
                    val glb = GlbWriter.writeCutout(w.meters, h.meters, cutoutPng(image, texSide), GlbWriter.CutoutAlpha.BLEND, name = name)
                    for (side in FRONT_LONG_SIDES) for (q in FRONT_QUALITIES) {
                        val img = picture(side)
                        val photo = photos.getOrPut(side to q) { img.webp(q) }
                        val out = Encoded(glb, photo, thumb, img.width, img.height, q)
                        if (out.totalBytes <= MAX_SCAN_BYTES) return out
                    }
                }
            }
        } finally {
            pictures.values.forEach { it.release() }
        }
        return plain()
    }

    /**
     * A cutout GLB at W × H from its stored picture ([photo], the item's `cutout.webp`) within [budget] bytes (edits,
     * repairs, stale models). A transparent picture steps its PNG texture down; a missing or undecodable picture gives
     * a plain panel.
     */
    private fun cutoutFromPhoto(w: Dimension, h: Dimension, name: String, photo: ByteArray?, budget: Long): ByteArray {
        val plain = { GlbWriter.writeCutout(w.meters, h.meters, null, colorSrgb = FRONT_COLOR, name = name) }
        val decoded = photo?.let { codec.decode(it, FRONT_LONG_SIDES.first()) } ?: return plain()
        try {
            val pixels = decoded.pixels()
            if (pixels.isOpaque) {
                for (q in FRONT_QUALITIES) {
                    val glb = GlbWriter.writeCutout(w.meters, h.meters, decoded.jpeg(q), name = name)
                    if (glb.size <= budget) return glb
                }
            } else {
                for (texSide in CUTOUT_TEXTURE_SIDES) {
                    val glb = GlbWriter.writeCutout(w.meters, h.meters, cutoutPng(pixels, texSide), GlbWriter.CutoutAlpha.BLEND, name = name)
                    if (glb.size <= budget) return glb
                }
            }
        } finally {
            decoded.release()
        }
        return plain()
    }

    /** The straight-alpha PNG texture of a transparent cutout at [side] px, edges bled so no halo can appear. */
    private fun cutoutPng(image: ArgbImage, side: Int): ByteArray {
        val small = CutoutPixels.bleedEdges(CutoutPixels.downscale(image, side))
        return PngEncoder.encodeRgba(small.width, small.height, small.argb)
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
            origin = originOf(kind),
            name = displayName(kind, label),
            modelKind = ModelKind.PROXY_BOX,
        )

    private fun originOf(item: GalleryItem): BoxOrigin = when (item.modelKind) {
        ModelKind.PROXY_BOX -> originOf(item.kind)
        ModelKind.CUTOUT -> BoxOrigin.CENTER
        else -> BoxOrigin.BOTTOM_CENTER
    }

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
            modelKind = enumOr(modelKind, ModelKind.PROXY_BOX),
            templateId = templateId,
            meshExtents = meshExtentsOf(),
            glbExtents = glbExtentsOf(),
            meshInfo = meshInfo,
            captureMethod = captureMethod?.let { c -> CaptureMethod.entries.firstOrNull { it.name == c } },
        ).also { item -> listOf(item.width, item.height, item.depth).forEach { validate(it, forSave = false) } }
    }.getOrNull()

    private fun GalleryItemEntity.meshExtentsOf(): MeshExtents? {
        val w = meshExtentW; val h = meshExtentH; val d = meshExtentD
        if (w == null || h == null || d == null || !isValidSide(w) || !isValidSide(h) || !isValidSide(d)) return null
        fun b(x: Double?) = x?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        return MeshExtents(w, h, d, b(meshExtentWBound95), b(meshExtentHBound95), b(meshExtentDBound95))
    }

    private fun GalleryItemEntity.glbExtentsOf(): GlbExtents? {
        val w = meshGlbW; val h = meshGlbH; val d = meshGlbD
        if (w == null || h == null || d == null || !(w > 0 && h > 0 && d > 0) || !isValidSide(w) || !isValidSide(h) || !isValidSide(d)) return null
        return GlbExtents(w, h, d)
    }

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
        private const val PLACE_DIR = "place"

        /** GLB extras must match the row to this (m): 0.1 mm (§3.3). */
        const val PLACEMENT_MATCH_M = 1e-4

        internal fun matches(info: GlbInfo, w: Double, h: Double, d: Double): Boolean =
            abs(info.widthM - w) <= PLACEMENT_MATCH_M && abs(info.heightM - h) <= PLACEMENT_MATCH_M && abs(info.depthM - d) <= PLACEMENT_MATCH_M

        /** Wall items (FRAME, TV) hang from their back face; everything else stands on its bottom face. */
        fun originOf(kind: ObjectKind): BoxOrigin =
            if (kind == ObjectKind.FRAME || kind == ObjectKind.TV) BoxOrigin.BACK_CENTER else BoxOrigin.BOTTOM_CENTER

        private fun displayName(kind: ObjectKind, label: String?): String =
            cleanLabel(label) ?: kind.name.lowercase().replaceFirstChar { it.uppercase() }

        const val MODEL = "model.glb"
        const val PHOTO = "front.jpg"

        /** A cutout's picture (PLAN v3 W2): lossy WebP with alpha. */
        const val CUTOUT_PHOTO = "cutout.webp"

        /**
         * Long sides tried, in order, for a transparent cutout's PNG texture: an RGBA PNG is several times a JPEG, so it
         * steps down first within [MAX_SCAN_BYTES] (the stored picture stays sharper for the gallery).
         */
        internal val CUTOUT_TEXTURE_SIDES = intArrayOf(512, 448, 384, 320, 256)
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
