package com.spaceview.app.place

import androidx.lifecycle.SavedStateHandle
import com.spaceview.ar.SurfaceTarget
import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.data.gallery.PlacementModel
import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.Fit
import com.spaceview.engine.ModelKind
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** §3.9 `:app`: PlaceViewModel state, SavedStateHandle round-trip, deletion, errors; the ads contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private inner class FakeSource(var model: PlacementModel?, var fail: Boolean = false, var storageError: Boolean = false) : PlaceModelSource {
        val exists = MutableStateFlow(true)
        var calls = 0
        override suspend fun lookup(id: Long): PlacementLookup {
            calls++
            if (fail) throw IOException("disk")
            if (storageError) return PlacementLookup.Failed
            return model?.let { PlacementLookup.Ready(it) } ?: PlacementLookup.Missing
        }
        override fun exists(id: Long) = exists
    }

    private fun model(origin: BoxOrigin = BoxOrigin.BACK_CENTER, kind: ObjectKind = ObjectKind.FRAME): PlacementModel {
        val f = File(tmp.root, "m.glb").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return PlacementModel(
            7, f, 0.6, 0.4, 0.03, origin, kind,
            Dimension.measured(0.6, 0.01), Dimension.measured(0.4, 0.01), Dimension.preset(0.03),
            ModelKind.PROXY_BOX, false, "Frame",
        )
    }

    private fun vm(source: PlaceModelSource, saved: SavedStateHandle = SavedStateHandle()) =
        PlaceViewModel(7, saved, source, flowOf(UnitPreference.METRIC), io = dispatcher)

    @Test fun loadsAndPicksTheWallForWallItems() {
        val v = vm(FakeSource(model()))
        val ready = v.load.value as PlaceLoad.Ready
        assertEquals(3, ready.loaded.bytes.size)
        assertEquals(SurfaceTarget.WALL, v.target.value)
        assertFalse(v.restoredAfterDeath)
        assertEquals(SurfaceTarget.FLOOR, vm(FakeSource(model(BoxOrigin.BOTTOM_CENTER, ObjectKind.BOX))).target.value)
    }

    @Test fun savedStateRoundTrip() {
        val saved = SavedStateHandle()
        val a = vm(FakeSource(model()), saved)
        a.setTarget(SurfaceTarget.TABLE)
        a.setSpinDeg(90)
        val space = SpaceReading(SpaceKind.AREA, 0.8, 0.004, 1.2, 0.005, primaryHorizontal = false)
        a.setSpace(space)
        // Process death: a new view model on the same SavedStateHandle.
        val b = vm(FakeSource(model()), saved)
        assertTrue(b.restoredAfterDeath)
        assertEquals(SurfaceTarget.TABLE, b.target.value)
        assertEquals(90, b.spinDeg)
        assertEquals(space, b.space.value)
        b.setSpace(null)
        assertNull(vm(FakeSource(model()), saved).space.value)
        // A gap keeps its null secondary through the round trip.
        b.setSpace(SpaceReading(SpaceKind.GAP, 1.0, 0.01))
        assertEquals(SpaceReading(SpaceKind.GAP, 1.0, 0.01), vm(FakeSource(model()), saved).space.value)
    }

    @Test fun deletedMissingAndFailed() {
        val src = FakeSource(model())
        val v = vm(src)
        assertTrue(v.load.value is PlaceLoad.Ready)
        src.exists.value = false
        assertEquals(PlaceLoad.Deleted, v.load.value)
        assertEquals(PlaceLoad.Deleted, vm(FakeSource(null)).load.value)
        val failing = FakeSource(model(), fail = true)
        val f = vm(failing)
        assertEquals(PlaceLoad.Failed, f.load.value)
        failing.fail = false
        f.reload()
        assertTrue(f.load.value is PlaceLoad.Ready)
        assertEquals(2, failing.calls)
    }

    @Test fun verdictCopyInputsForMeasuredAndTyped() {
        val v = vm(FakeSource(model()))
        val m = (v.load.value as PlaceLoad.Ready).loaded.model
        // Frame 0.6 × 0.4 (± 1 cm each) on a wall space 0.7 × 0.5 (± 0.5 cm): 10 cm spare > 1.5 cm band.
        val fit = v.verdict(m, SurfaceTarget.WALL, SpaceReading(SpaceKind.AREA, 0.7, 0.005 / 1.96, 0.5, 0.005 / 1.96))!!
        assertEquals(Fit.FITS, fit.fit)
        assertFalse(fit.usedTypedSize)
        assertNull(v.verdict(m, SurfaceTarget.WALL, null))
        // Typed width: exact; the card says "using your typed size".
        val typed = PlacementModel(m.id, m.file, 0.6, 0.4, 0.03, m.origin, m.kind, Dimension.typed(0.6), m.height, m.depth, m.modelKind, false, null)
        val g = v.verdict(typed, SurfaceTarget.WALL, SpaceReading(SpaceKind.GAP, 0.62, 0.005 / 1.96))!!
        assertEquals(Fit.FITS, g.fit)
        assertTrue(g.usedTypedSize)
        assertTrue(g.heightNotChecked)
    }

    @Test fun placeNeverOwesAnAd() {
        // K2 / D7: nothing under app/place records a scan, opens a Measure visit or shows an interstitial.
        val dir = File("src/main/kotlin/com/spaceview/app/place")
        assertTrue(dir.absolutePath, dir.isDirectory)
        val forbidden = listOf("recordCompletedScan", "onMeasureEntered", "onMeasureLeaving", "maybeShowAfterSave", "preloadInterstitial")
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".kt") }) {
            val text = f.readText()
            for (word in forbidden) assertFalse("${f.name} calls $word", text.contains("$word("))
        }
        assertTrue(File(dir, "PlaceScreen.kt").readText().contains("ArScreenAdGuard("))
    }

    @Test fun primaryHorizontalFromCorners() {
        // Wall rectangle 1.2 wide × 0.8 tall: first edge vertical, second horizontal.
        val up = Vec3(0.0, 0.8, 0.0)
        val across = Vec3(1.2, 0.0, 0.0)
        assertFalse(PlaceGeometry.primaryIsHorizontal(0.8, 1.2, up, across))
        assertTrue(PlaceGeometry.primaryIsHorizontal(1.2, 0.8, up, across))
        assertTrue(PlaceGeometry.primaryIsHorizontal(1.2, 0.8, across, up))
    }

    @Test fun storageFailureIsAnErrorNotDeleted() {
        // M4 review MINOR 5: a full disk during regeneration must show the error card, never "deleted".
        val src = FakeSource(model(), storageError = true)
        val v = vm(src)
        assertEquals(PlaceLoad.Failed, v.load.value)
        src.storageError = false
        v.reload()
        assertTrue(v.load.value is PlaceLoad.Ready)
    }

    @Test fun heightIsSavedAndSpaceIsLabelledAfterAPauseOrRestart() {
        val saved = SavedStateHandle()
        val a = vm(FakeSource(model()), saved)
        assertNull(a.centreHeightM)
        a.setCentreHeight(1.45)
        a.setCentreHeight(Double.NaN) // ignored
        assertEquals(1.45, vm(FakeSource(model()), saved).centreHeightM!!, 0.0)
        // Measured at pause count 3: fresh until the session pauses again.
        assertFalse(a.spaceMeasuredBeforePause(3))
        a.setSpace(SpaceReading(SpaceKind.GAP, 1.0, 0.01), pausesNow = 3)
        assertFalse(a.spaceMeasuredBeforePause(3))
        assertTrue(a.spaceMeasuredBeforePause(4))
        // After process death the restored reading is always from before.
        val b = vm(FakeSource(model()), saved)
        assertTrue(b.spaceMeasuredBeforePause(0))
        b.setSpace(SpaceReading(SpaceKind.GAP, 1.1, 0.01), pausesNow = 0)
        assertFalse(b.spaceMeasuredBeforePause(0))
    }
}
