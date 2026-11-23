package com.spaceview.app.smart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SM1b on the device: [LabelObjectNamer] over ML Kit's base image labeller, **unbundled** (served by Google Play
 * services; the model is not in the APK and is fetched at install via the manifest's `ica` meta-data). Inference is
 * on-device; the photo never leaves the phone. Not yet downloaded, no Play services or any ML Kit error → no
 * suggestion. Cheap to create: ML Kit is initialised and a labeller opened only when [suggest] runs.
 */
fun mlKitObjectNamer(context: Context): ObjectNamer =
    LabelObjectNamer(MlKitImageLabelSource(context.applicationContext), onFailure = ::warnOnce)

private val warned = AtomicBoolean(false)

/** One WARN per process with the failure reason only (no image, no labels): enough to notice a broken ML Kit bump. */
private fun warnOnce(reason: String) {
    if (warned.compareAndSet(false, true)) runCatching { Log.w("ObjectNamer", "No name suggestion: $reason") }
}

/** ML Kit's base labeller as an [ImageLabelSource]. Called on a background dispatcher ([LabelObjectNamer]). */
internal class MlKitImageLabelSource(private val appContext: Context) : ImageLabelSource {
    override suspend fun labels(jpeg: ByteArray): List<RawLabel> {
        initMlKit(appContext)
        val bitmap = decode(jpeg) ?: throw IOException("Photo could not be decoded")
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(LABELLER_THRESHOLD).build(),
        )
        val task = try {
            labeler.process(InputImage.fromBitmap(bitmap, 0))
        } catch (e: Exception) {
            labeler.close()
            throw e
        }
        // Closed when the task ends, never while it runs (a timeout only stops waiting). The bitmap is left to the GC
        // for the same reason: ML Kit may still read it after a timeout.
        task.addOnCompleteListener { labeler.close() }
        val result = suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { cont.resume(it) }
            task.addOnFailureListener { cont.resumeWithException(it) }
            // ML Kit cancelling its own task is a failure, not the caller's cancellation (SM1 review MINOR 12).
            task.addOnCanceledListener { cont.resumeWithException(IOException("ML Kit cancelled the labelling task")) }
        }
        return result.map { RawLabel(it.text, it.confidence) }
    }

    /** The photo at ≤ [MAX_SIDE_PX] on its long side (the model looks at 224 px; less memory, faster). */
    private fun decode(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        var sample = 1
        while (longSide / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    private companion object {
        const val MAX_SIDE_PX = 448

        /** ML Kit drops labels below this; [LabelMapper.MIN_CONFIDENCE] then decides what is offered. */
        const val LABELLER_THRESHOLD = 0.5f
    }
}

/**
 * The manifest removes ML Kit's `MlKitInitProvider` (it would run at every cold start), so ML Kit is initialised here,
 * on first use, off the main thread.
 *
 * `MlKitContext.initializeIfNeeded` lives in ML Kit's `sdkinternal` package (`@KeepForSdk`, idempotent). If a future
 * ML Kit version drops or changes it, fall back to the public `MlKit.initialize`, which runs the same setup but may
 * throw when ML Kit is already initialised (that is fine: it then is). **Bumping ML Kit requires the TESTING §21 check
 * "chip appears with the model present"**, because a semantic change here fails silently (only the log tells).
 */
private fun initMlKit(appContext: Context) {
    try {
        MlKitContext.initializeIfNeeded(appContext)
    } catch (e: LinkageError) {
        runCatching { MlKit.initialize(appContext) }
    }
}
