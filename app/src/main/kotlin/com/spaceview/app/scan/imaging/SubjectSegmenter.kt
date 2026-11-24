package com.spaceview.app.scan.imaging

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Finds the object in the straightened front photo (PLAN v3 W2): one foreground confidence (0..1) per pixel, or null
 * when it cannot run. Never throws (except cancellation).
 */
fun interface SubjectSegmenter {
    suspend fun mask(photo: Bitmap): FloatArray?
}

/**
 * [SubjectSegmenter] on ML Kit **Subject Segmentation** (`play-services-mlkit-subject-segmentation`, beta). It is the
 * unbundled Google Play services variant, the only one ML Kit offers: the model is not in the APK; Play services
 * downloads it (the manifest's `com.google.mlkit.vision.DEPENDENCIES` asks for `subject_segment` at install). Inference
 * runs on the phone; the photo never leaves it. Not downloaded yet, no Play services, or any ML Kit error → null, and
 * the Review keeps the rectangle and says so.
 */
fun mlKitSubjectSegmenter(context: Context): SubjectSegmenter {
    val app = context.applicationContext
    return SubjectSegmenter { photo ->
        try {
            segment(app, photo)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            warnOnce(e.javaClass.simpleName + ": " + e.message)
            null
        } catch (e: LinkageError) {
            warnOnce("ML Kit missing: ${e.message}")
            null
        }
    }
}

private val warned = AtomicBoolean(false)

/** One WARN per process with the reason only (no image data). */
private fun warnOnce(reason: String) {
    if (warned.compareAndSet(false, true)) runCatching { Log.w("SubjectSegmenter", "No cutout: $reason") }
}

private suspend fun segment(appContext: Context, photo: Bitmap): FloatArray? {
    // The manifest removes ML Kit's init provider (MlKitObjectNamer has the same first-use initialisation).
    try {
        MlKitContext.initializeIfNeeded(appContext)
    } catch (e: LinkageError) {
        runCatching { MlKit.initialize(appContext) }
    }
    val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    val task = try {
        segmenter.process(InputImage.fromBitmap(photo, 0))
    } catch (e: Exception) {
        segmenter.close()
        throw e
    }
    // Closed when the task ends, never while it runs (a timeout only stops waiting).
    task.addOnCompleteListener { segmenter.close() }
    val result = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
        task.addOnFailureListener { cont.resumeWithException(it) }
        task.addOnCanceledListener { cont.resumeWithException(IOException("ML Kit cancelled the segmentation task")) }
    }
    val buffer = result.foregroundConfidenceMask ?: return null
    buffer.rewind()
    val n = photo.width * photo.height
    if (buffer.remaining() < n) return null
    return FloatArray(n).also { buffer.get(it) }
}
