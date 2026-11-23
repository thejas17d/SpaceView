package com.spaceview.app.smart

import com.spaceview.app.R
import com.spaceview.data.gallery.ObjectKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** SM1b (K6): the curated mapping and every "no suggestion" path, behind a fake labeller. */
class ObjectNamerTest {
    private val jpeg = byteArrayOf(1, 2, 3)

    /** A labeller that answers [labels], or throws [error]. */
    private class FakeLabels(val labels: List<RawLabel> = emptyList(), val error: Exception? = null) : ImageLabelSource {
        var calls = 0
        override suspend fun labels(jpeg: ByteArray): List<RawLabel> {
            calls++
            error?.let { throw it }
            return labels
        }
    }

    @Test fun theMostConfidentCuratedLabelWins() = runTest {
        val n = LabelObjectNamer(
            FakeLabels(listOf(RawLabel("Room", 0.97f), RawLabel("Chair", 0.74f), RawLabel("Couch", 0.91f))),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val s = n.suggest(jpeg, ObjectKind.FURNITURE)
        assertEquals("sofa", s?.id)
        assertEquals(R.string.smart_name_sofa, s?.nameRes)
        assertEquals(0.91f, s!!.confidence, 0f)
    }

    @Test fun labelsOutsideTheCuratedListAreNeverOffered() {
        // "Room" and "Smile" are real ML Kit labels, but not things we name; nothing raw is ever shown (D11).
        assertNull(LabelMapper.map(listOf(RawLabel("Room", 0.99f), RawLabel("Smile", 0.95f), RawLabel("Sofa", 0.99f)), null))
        // Matching is on the model's exact English label text (its label map), ignoring stray spaces.
        assertEquals("tv", LabelMapper.map(listOf(RawLabel(" Television ", 0.9f)), null)?.id)
        assertNull(LabelMapper.map(listOf(RawLabel("television", 0.9f)), null))
    }

    @Test fun lowConfidenceOrNonsenseConfidenceGivesNothing() {
        val below = LabelMapper.MIN_CONFIDENCE - 0.01f
        assertNull(LabelMapper.map(listOf(RawLabel("Couch", below)), null))
        assertNull(LabelMapper.map(listOf(RawLabel("Couch", Float.NaN)), null))
        assertNull(LabelMapper.map(listOf(RawLabel("Couch", 1.5f)), null))
        assertNull(LabelMapper.map(emptyList(), null))
        assertEquals("sofa", LabelMapper.map(listOf(RawLabel("Couch", LabelMapper.MIN_CONFIDENCE)), null)?.id)
    }

    @Test fun aNameMustFitThePickedKind() {
        val labels = listOf(RawLabel("Couch", 0.95f), RawLabel("Poster", 0.80f))
        // A frame scan never suggests "Sofa"; it falls through to the next curated label that fits.
        assertEquals("poster", LabelMapper.map(labels, ObjectKind.FRAME)?.id)
        assertEquals("sofa", LabelMapper.map(labels, ObjectKind.FURNITURE)?.id)
        // "Other" accepts every curated name; no kind (null) too.
        assertEquals("sofa", LabelMapper.map(labels, ObjectKind.OTHER)?.id)
        assertEquals("sofa", LabelMapper.map(labels, null)?.id)
        assertNull(LabelMapper.map(listOf(RawLabel("Couch", 0.95f)), ObjectKind.TV))
        assertEquals("tv", LabelMapper.map(listOf(RawLabel("Television", 0.8f)), ObjectKind.TV)?.id)
    }

    @Test fun modelUnavailableOrAnyErrorGivesNoSuggestion() = runTest {
        // ML Kit says "waiting for the optional module to be downloaded" / no Play services / bad photo.
        val source = FakeLabels(error = IllegalStateException("UNAVAILABLE: model not downloaded"))
        assertNull(LabelObjectNamer(source, dispatcher = StandardTestDispatcher(testScheduler)).suggest(jpeg, null))
        assertEquals(1, source.calls)
        assertNull(LabelObjectNamer(FakeLabels(error = IOException("decode")), dispatcher = StandardTestDispatcher(testScheduler)).suggest(jpeg))
        // An empty photo never reaches the labeller.
        val untouched = FakeLabels(listOf(RawLabel("Couch", 0.9f)))
        assertNull(LabelObjectNamer(untouched, dispatcher = StandardTestDispatcher(testScheduler)).suggest(ByteArray(0)))
        assertEquals(0, untouched.calls)
    }

    @Test fun aLabellerThatNeverAnswersTimesOut() = runTest {
        val hanging = object : ImageLabelSource {
            override suspend fun labels(jpeg: ByteArray): List<RawLabel> = awaitCancellation()
        }
        val n = LabelObjectNamer(hanging, timeoutMs = 3_000, dispatcher = StandardTestDispatcher(testScheduler))
        var result: NameSuggestion? = NameSuggestion("x", 0, 1f)
        var done = false
        launch { result = n.suggest(jpeg); done = true }
        advanceUntilIdle()
        assertTrue(done)
        assertNull(result)
        assertTrue("virtual time ${testScheduler.currentTime}", testScheduler.currentTime >= 3_000)
    }

    @Test fun theCallersCancellationIsNotSwallowed() = runTest {
        val hanging = object : ImageLabelSource {
            override suspend fun labels(jpeg: ByteArray): List<RawLabel> = awaitCancellation()
        }
        val n = LabelObjectNamer(hanging, timeoutMs = 60_000, dispatcher = StandardTestDispatcher(testScheduler))
        var cancelled = false
        val job = launch {
            try {
                n.suggest(jpeg)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        runCurrent() // waiting inside the labeller; virtual time has not moved, so the 60 s timeout has not fired
        assertTrue(job.isActive)
        job.cancel() // e.g. the user left the Review: the view model's scope is cancelled
        advanceUntilIdle()
        assertTrue(cancelled)
    }

    /** SM1 review MINOR 12: ML Kit cancelling its own task gives no suggestion (not a cancelled caller), and every failure is reported. */
    @Test fun failuresAreReportedAndAnInnerCancellationIsJustNoSuggestion() = runTest {
        val reasons = ArrayList<String>()
        val innerCancel = object : ImageLabelSource {
            override suspend fun labels(jpeg: ByteArray): List<RawLabel> = throw CancellationException("ML Kit task cancelled")
        }
        val n = LabelObjectNamer(innerCancel, dispatcher = StandardTestDispatcher(testScheduler), onFailure = { reasons += it })
        assertNull(n.suggest(jpeg)) // returns normally: the caller is still active
        LabelObjectNamer(FakeLabels(error = IOException("decode")), dispatcher = StandardTestDispatcher(testScheduler), onFailure = { reasons += it })
            .suggest(jpeg)
        val hanging = object : ImageLabelSource {
            override suspend fun labels(jpeg: ByteArray): List<RawLabel> = awaitCancellation()
        }
        LabelObjectNamer(hanging, timeoutMs = 100, dispatcher = StandardTestDispatcher(testScheduler), onFailure = { reasons += it })
            .suggest(jpeg)
        assertEquals(3, reasons.size)
        assertTrue(reasons[0].startsWith("cancelled"))
        assertEquals("java.io.IOException", reasons[1])
        assertEquals("timeout", reasons[2])
        // A plain "nothing curated" answer is not a failure.
        LabelObjectNamer(FakeLabels(listOf(RawLabel("Room", 0.9f))), dispatcher = StandardTestDispatcher(testScheduler), onFailure = { reasons += it })
            .suggest(jpeg)
        assertEquals(3, reasons.size)
    }

    /** Every curated name exists in English and Hindi (no mixed-script UI, D11), and ids are unique per name. */
    @Test fun everyCuratedNameHasEnglishAndHindiText() {
        val en = stringKeys(File("src/main/res/values/strings_smart.xml"))
        val hi = stringKeys(File("src/main/res/values-hi/strings_smart.xml"))
        assertEquals(en, hi)
        val names = LabelMapper.table.values.distinctBy { it.id }
        assertEquals(names.size, names.map { it.nameRes }.toSet().size)
        for (c in names) assertTrue("${c.id} has no smart_name_${c.id}", "smart_name_${c.id}" in en)
        assertTrue(LabelMapper.table.size >= 40)
    }

    private fun stringKeys(f: File): Set<String> {
        assertNotNull(f.takeIf { it.exists() })
        return Regex("""<string name="([a-z0-9_]+)"""").findAll(f.readText()).map { it.groupValues[1] }.toSet()
    }
}
