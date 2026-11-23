package com.spaceview.app.scan

import androidx.lifecycle.SavedStateHandle
import com.spaceview.app.R
import com.spaceview.app.smart.NameSuggestion
import com.spaceview.app.smart.ObjectNamer
import com.spaceview.ar.FaceChoice
import com.spaceview.ar.FrontFace
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.data.gallery.ScanDraft
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/** SM1b at the scan Review: the name chip is offered once per photo and never applied without a tap. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanNamingTest {
    private object NoStore : ScanStore {
        override suspend fun save(draft: ScanDraft): Long = 1L
        override suspend fun get(id: Long): GalleryItem? = null
        override suspend fun shareCopy(id: Long, fileName: String): File? = null
    }

    private class FakeNamer(val answer: () -> NameSuggestion?) : ObjectNamer {
        val kinds = ArrayList<ObjectKind?>()
        override suspend fun suggest(jpeg: ByteArray, kind: ObjectKind?): NameSuggestion? {
            kinds += kind
            return answer()
        }
    }

    private val sofa = NameSuggestion("sofa", R.string.smart_name_sofa, 0.9f)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(namer: ObjectNamer?) =
        ScanViewModel(SavedStateHandle(), flowOf(ScanSettings(loaded = true)), NoStore, { null }, {}, namer = namer)

    private fun outline(): ScanOutline {
        val face = FrontFace(listOf(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3.ZERO), Vec3.UNIT_Z)
        fun m(v: Double, b: Double) = Measurement(v, b / Uncertainty.K95)
        return ScanOutline(
            ScanShape.BOX, m(1.8, 0.02), m(0.8, 0.02), m(0.9, 0.02), null, Quality.FAIR, false, true, face,
            FaceChoice(0, Vec3.UP, Vec3.UNIT_Z),
        )
    }

    private fun atReview(v: ScanViewModel): ScanViewModel {
        v.chooseKind(ObjectKind.BOX)
        v.onOutline(outline(), null, DeviceTier.NO_DEPTH)
        v.skipPhoto()
        return v
    }

    @Test fun theSuggestionIsOfferedButNeverApplied() {
        val namer = FakeNamer { sofa }
        val v = atReview(vm(namer))
        v.suggestName(byteArrayOf(1))
        assertEquals(sofa, v.state.value.nameSuggestion)
        assertNull(v.state.value.label) // the name field still shows the type's default until the user taps the chip
        assertEquals(listOf<ObjectKind?>(ObjectKind.BOX), namer.kinds)
        // Tapping the chip is the screen calling setLabel with the localised name.
        v.setLabel("Sofa")
        assertEquals("Sofa", v.state.value.label)
    }

    @Test fun noModelOrAThrowingNamerMeansNoChipAndNoCrash() {
        val none = atReview(vm(FakeNamer { null }))
        none.suggestName(byteArrayOf(1))
        assertNull(none.state.value.nameSuggestion)
        val broken = atReview(vm(FakeNamer { throw IllegalStateException("bug") }))
        broken.suggestName(byteArrayOf(1))
        assertNull(broken.state.value.nameSuggestion)
        // No namer at all (tests, or naming switched off): nothing happens.
        val off = atReview(vm(null))
        off.suggestName(byteArrayOf(1))
        assertNull(off.state.value.nameSuggestion)
    }

    @Test fun measuringAgainDropsTheSuggestionAndCancelsAPendingOne() {
        val v = atReview(vm(FakeNamer { sofa }))
        v.suggestName(byteArrayOf(1))
        v.remeasure()
        assertNull(v.state.value.nameSuggestion)
        // A namer still working when the photo is dropped never lands its answer later.
        val slow = object : ObjectNamer {
            override suspend fun suggest(jpeg: ByteArray, kind: ObjectKind?): NameSuggestion? = awaitCancellation()
        }
        val w = atReview(vm(slow))
        w.suggestName(byteArrayOf(1))
        w.backToType()
        assertNull(w.state.value.nameSuggestion)
    }
}
