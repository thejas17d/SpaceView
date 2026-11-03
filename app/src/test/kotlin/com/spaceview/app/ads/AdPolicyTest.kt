package com.spaceview.app.ads

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeClock(var now: Long = START) : AdClock {
    override fun nowMillis(): Long = now
    override fun epochDay(millis: Long): Long = Math.floorDiv(millis, AdClock.DAY_MILLIS)
    fun advanceMinutes(m: Long) { now += m * 60_000 }

    companion object {
        /** 2026-11-03 09:00 UTC. */
        const val START = 20_395L * AdClock.DAY_MILLIS + 9 * 3_600_000L
    }
}

class InMemoryAdCounterStore(var counters: AdCounters = AdCounters()) : AdCounterStore {
    override suspend fun read(): AdCounters = counters
    override suspend fun update(transform: (AdCounters) -> AdCounters): AdCounters =
        transform(counters).also { counters = it }
}

class AdPolicyTest {
    private val clock = FakeClock()
    private val store = InMemoryAdCounterStore()
    private val policy = AdPolicy(store, clock)

    /** Saves a scan, then leaves via Done; shows (and records) the ad when allowed. */
    private suspend fun saveAndExit(action: NextAction = NextAction.DONE): AdDecision {
        policy.recordCompletedScan()
        val d = policy.decide(action)
        if (d == AdDecision.SHOW) policy.recordInterstitialShown()
        return d
    }

    @Test
    fun noAdDuringFirstThreeScans_firstAdOnFourth() = runTest {
        repeat(3) { assertEquals(AdDecision.GRACE_PERIOD, saveAndExit()) }
        assertEquals(AdDecision.SHOW, saveAndExit())
    }

    @Test
    fun atMostOnePerThreeScans() = runTest {
        repeat(3) { saveAndExit() }
        assertEquals(AdDecision.SHOW, saveAndExit()) // scan 4
        clock.advanceMinutes(10)
        assertEquals(AdDecision.TOO_FEW_SCANS, saveAndExit()) // 5
        clock.advanceMinutes(10)
        assertEquals(AdDecision.TOO_FEW_SCANS, saveAndExit()) // 6
        clock.advanceMinutes(10)
        assertEquals(AdDecision.SHOW, saveAndExit()) // 7
    }

    @Test
    fun atLeastThreeMinutesApart() = runTest {
        repeat(4) { saveAndExit() } // ad shown at scan 4
        repeat(2) { policy.recordCompletedScan() }
        clock.advanceMinutes(2)
        assertEquals(AdDecision.TOO_SOON, saveAndExit()) // 3 scans since, but only 2 min
        clock.now += 59_999
        assertEquals(AdDecision.TOO_SOON, policy.decide(NextAction.DONE)) // 2:59.999
        clock.now += 1
        assertEquals(AdDecision.SHOW, policy.decide(NextAction.DONE)) // exactly 3:00
    }

    @Test
    fun atMostFourPerDay_resetsNextDay() = runTest {
        repeat(3) { saveAndExit() }
        var shown = 0
        repeat(30) {
            clock.advanceMinutes(5)
            if (saveAndExit() == AdDecision.SHOW) shown++
        }
        assertEquals(4, shown)
        assertEquals(4, store.counters.interstitialsOnDay)
        assertEquals(AdDecision.DAILY_CAP, policy.decide(NextAction.DONE))
        clock.now = (clock.epochDay(clock.now) + 1) * AdClock.DAY_MILLIS + 60_000 // 00:01 next day
        assertEquals(AdDecision.SHOW, policy.decide(NextAction.DONE))
        policy.recordInterstitialShown()
        assertEquals(1, store.counters.interstitialsOnDay)
    }

    @Test
    fun neverWhenNextActionIsPlaceInRoomOrShare() = runTest {
        repeat(10) { policy.recordCompletedScan() }
        assertEquals(AdDecision.BLOCKED_NEXT_ACTION, policy.decide(NextAction.PLACE_IN_ROOM))
        assertEquals(AdDecision.BLOCKED_NEXT_ACTION, policy.decide(NextAction.SHARE))
        assertEquals(AdDecision.SHOW, policy.decide(NextAction.BACK))
        assertEquals(AdDecision.SHOW, policy.decide(NextAction.DONE))
    }

    @Test
    fun placeInRoomDoesNotConsumeTheSlot() = runTest {
        repeat(4) { policy.recordCompletedScan() }
        assertEquals(AdDecision.BLOCKED_NEXT_ACTION, policy.decide(NextAction.PLACE_IN_ROOM))
        // Next save leaves via Done: still owed.
        assertEquals(AdDecision.SHOW, saveAndExit())
    }

    @Test
    fun neverOnCameraScreens() = runTest {
        repeat(10) { policy.recordCompletedScan() }
        for (action in NextAction.entries) {
            assertEquals(AdDecision.BLOCKED_CAMERA_SCREEN, policy.decide(action, AdScreen.CAMERA_AR))
        }
    }

    @Test
    fun clockMovedBackwardsDoesNotBlockForever() = runTest {
        repeat(4) { saveAndExit() }
        repeat(3) { policy.recordCompletedScan() }
        clock.now -= 2 * AdClock.DAY_MILLIS
        assertEquals(AdDecision.SHOW, policy.decide(NextAction.DONE))
    }

    @Test
    fun nextSaveMayShow_tracksPreloadWindow() = runTest {
        assertFalse(policy.nextSaveMayShow())
        repeat(2) { policy.recordCompletedScan() }
        assertFalse(policy.nextSaveMayShow()) // next is scan 3: still grace
        policy.recordCompletedScan()
        assertTrue(policy.nextSaveMayShow()) // next is scan 4
        policy.recordCompletedScan()
        policy.recordInterstitialShown()
        assertFalse(policy.nextSaveMayShow())
        repeat(2) { policy.recordCompletedScan() }
        assertTrue(policy.nextSaveMayShow())
    }

    @Test
    fun rulesAreConfigurable() = runTest {
        val strict = AdPolicy(InMemoryAdCounterStore(), clock, AdRules(freeScans = 0, scansPerInterstitial = 1, maxPerDay = 1))
        strict.recordCompletedScan()
        assertEquals(AdDecision.SHOW, strict.decide(NextAction.DONE))
        strict.recordInterstitialShown()
        clock.advanceMinutes(60)
        strict.recordCompletedScan()
        assertEquals(AdDecision.DAILY_CAP, strict.decide(NextAction.DONE))
    }

    @Test
    fun recordInterstitialShown_resetsScanCounterAndStampsTime() = runTest {
        repeat(5) { policy.recordCompletedScan() }
        policy.recordInterstitialShown()
        with(store.counters) {
            assertEquals(5, completedScans)
            assertEquals(0, scansSinceInterstitial)
            assertEquals(clock.now, lastInterstitialAtMillis)
            assertEquals(clock.epochDay(clock.now), interstitialDay)
            assertEquals(1, interstitialsOnDay)
        }
    }

    @Test
    fun systemClockEpochDayUsesLocalZone() {
        val now = System.currentTimeMillis()
        val tz = java.util.TimeZone.getDefault()
        assertEquals(Math.floorDiv(now + tz.getOffset(now), AdClock.DAY_MILLIS), AdClock.System.epochDay(now))
    }
}
