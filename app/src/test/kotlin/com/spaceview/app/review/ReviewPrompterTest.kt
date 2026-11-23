package com.spaceview.app.review

import android.app.Activity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryReviewStore(var record: ReviewRecord = ReviewRecord(), var fail: Boolean = false) : ReviewRecordStore {
    override suspend fun read(): ReviewRecord {
        if (fail) throw java.io.IOException("disk")
        return record
    }

    override suspend fun update(transform: (ReviewRecord) -> ReviewRecord): ReviewRecord {
        if (fail) throw java.io.IOException("disk")
        record = transform(record)
        return record
    }
}

class ReviewPolicyTest {
    private val day = ReviewRules.DAY_MILLIS
    private val now = 400L * day

    private fun decide(
        record: ReviewRecord = ReviewRecord(saves = 3),
        moment: Long? = now,
        ar: Boolean = false,
        adBusy: Boolean = false,
        lastAd: Long = 0L,
        at: Long = now,
    ) = ReviewPolicy.decide(record, moment, at, ar, adBusy, lastAd)

    @Test fun `asks after a fresh positive moment`() = assertEquals(ReviewDecision.ASK, decide())

    @Test fun `no moment - never asks`() = assertEquals(ReviewDecision.NO_MOMENT, decide(moment = null))

    @Test fun `a moment older than 30 minutes no longer counts`() =
        assertEquals(ReviewDecision.NO_MOMENT, decide(moment = now - 31 * 60_000L))

    @Test fun `never on an AR screen`() = assertEquals(ReviewDecision.AR_SCREEN, decide(ar = true))

    @Test fun `never while an interstitial is owed or showing`() = assertEquals(ReviewDecision.AFTER_INTERSTITIAL, decide(adBusy = true))

    @Test fun `never within 10 minutes after an interstitial`() {
        assertEquals(ReviewDecision.AFTER_INTERSTITIAL, decide(lastAd = now - 9 * 60_000L))
        assertEquals(ReviewDecision.ASK, decide(lastAd = now - 11 * 60_000L))
    }

    @Test fun `at most once per 90 days`() {
        assertEquals(ReviewDecision.TOO_SOON, decide(record = ReviewRecord(5, lastAskedAtMillis = now - 89 * day)))
        assertEquals(ReviewDecision.ASK, decide(record = ReviewRecord(5, lastAskedAtMillis = now - 90 * day)))
    }

    @Test fun `a clock set before the last ask never asks`() =
        assertEquals(ReviewDecision.TOO_SOON, decide(record = ReviewRecord(5, lastAskedAtMillis = now + day)))

    @Test fun `the 3rd save and later are moments, the first two are not`() {
        assertEquals(false, ReviewPolicy.saveIsMoment(ReviewRecord(saves = 2)))
        assertEquals(true, ReviewPolicy.saveIsMoment(ReviewRecord(saves = 3)))
        assertEquals(true, ReviewPolicy.saveIsMoment(ReviewRecord(saves = 40)))
    }
}

class ReviewPrompterTest {
    private val activity = Activity()
    private var now = 1_000L * ReviewRules.DAY_MILLIS
    private var ar = false
    private var adBusy = false
    private var lastAd = 0L
    private var resumed = true
    private val store = InMemoryReviewStore()
    private var launches = 0

    private fun TestScope.prompter() = ReviewPrompter(
        store = store,
        launcher = { _, onDone -> launches++; onDone() },
        arInForeground = { ar },
        adBusy = { adBusy },
        lastInterstitialAtMillis = { lastAd },
        isResumed = { resumed },
        clock = { now },
        main = StandardTestDispatcher(testScheduler),
        log = {},
    )

    @Test
    fun `third save arms the prompt - the next non-AR resume asks once and records it`() = runTest {
        val p = prompter()
        repeat(2) { p.onSaveCompleted() }
        advanceUntilIdle()
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals("two saves are not a moment", 0, launches)
        p.onSaveCompleted()
        advanceUntilIdle()
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(1, launches)
        assertEquals(now, store.record.lastAskedAtMillis)
        assertEquals(3, store.record.saves)
        // The moment was used up: another resume does not ask again.
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(1, launches)
    }

    @Test
    fun `a Fits verdict arms the prompt, but it waits for a non-AR screen`() = runTest {
        val p = prompter()
        ar = true
        p.onFitsVerdict()
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals("never over the camera", 0, launches)
        ar = false
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(1, launches)
    }

    @Test
    fun `never right after an interstitial - the moment stays armed for a later resume`() = runTest {
        val p = prompter()
        p.onFitsVerdict()
        adBusy = true
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(0, launches)
        adBusy = false
        lastAd = now - 60_000L // the ad was just shown
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(0, launches)
        now += 11 * 60_000L
        p.onFitsVerdict()
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(1, launches)
    }

    @Test
    fun `at most once per 90 days, even with new moments`() = runTest {
        val p = prompter()
        p.onFitsVerdict()
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(1, launches)
        now += 30L * ReviewRules.DAY_MILLIS
        p.onFitsVerdict()
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(1, launches)
        now += 61L * ReviewRules.DAY_MILLIS
        p.onFitsVerdict()
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(2, launches)
    }

    @Test
    fun `a paused screen does not ask`() = runTest {
        val p = prompter()
        p.onFitsVerdict()
        resumed = false
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(0, launches)
    }

    @Test
    fun `a broken store never crashes and never asks`() = runTest {
        val p = prompter()
        store.fail = true
        p.onSaveCompleted()
        p.onFitsVerdict()
        p.maybeAsk(activity)
        advanceUntilIdle()
        assertEquals(0, launches)
    }
}
