package com.spaceview.app.ads

import java.util.TimeZone

/**
 * Time source for [AdPolicy]. Injectable so the frequency rules can be unit-tested.
 */
interface AdClock {
    /** Wall-clock time in epoch milliseconds. */
    fun nowMillis(): Long

    /** Local calendar day (days since 1970-01-01 in the user's time zone) of [millis]. */
    fun epochDay(millis: Long): Long

    /** The device clock in the device's current time zone. */
    object System : AdClock {
        override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
        override fun epochDay(millis: Long): Long =
            Math.floorDiv(millis + TimeZone.getDefault().getOffset(millis), DAY_MILLIS)
    }

    companion object {
        const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}

/**
 * Persisted ad counters. Plain values so the policy stays pure.
 *
 * @property completedScans scans (or measurements) the user has saved, ever.
 * @property scansSinceInterstitial saved scans since the last interstitial was shown (all scans if none yet).
 * @property lastInterstitialAtMillis when the last interstitial was shown; 0 = never.
 * @property interstitialDay local [AdClock.epochDay] that [interstitialsOnDay] counts.
 * @property interstitialsOnDay interstitials shown on [interstitialDay].
 */
data class AdCounters(
    val completedScans: Int = 0,
    val scansSinceInterstitial: Int = 0,
    val lastInterstitialAtMillis: Long = 0L,
    val interstitialDay: Long = Long.MIN_VALUE,
    val interstitialsOnDay: Int = 0,
)

/** Storage for [AdCounters]. Production uses DataStore ([DataStoreAdCounterStore]). */
interface AdCounterStore {
    suspend fun read(): AdCounters

    /** Atomically replaces the counters with [transform] of the current ones; returns the new value. */
    suspend fun update(transform: (AdCounters) -> AdCounters): AdCounters
}

/** What the user chose when leaving the "Saved" result. */
enum class NextAction {
    /** "Done" button: the end of a content segment. The only place an interstitial may appear. */
    DONE,

    /** System back gesture from the Saved result; treated like [DONE]. */
    BACK,

    /** "Place in my room": the user is starting another task. Never interrupted by an ad. */
    PLACE_IN_ROOM,

    /** "Share": the user expects the share sheet. Never interrupted by an ad. */
    SHARE,
}

/** Kind of screen the request comes from. */
enum class AdScreen {
    /** Any non-camera screen (Saved result, Gallery, History, Settings). */
    NON_CAMERA,

    /** Camera / AR screens (measure, scan, calibrate, place). Never shows ads. */
    CAMERA_AR,
}

/** Result of an eligibility check; anything other than [SHOW] names the rule that blocked it. */
enum class AdDecision {
    SHOW,
    BLOCKED_CAMERA_SCREEN,
    BLOCKED_NEXT_ACTION,
    /** Still inside the first [AdRules.freeScans] completed scans. */
    GRACE_PERIOD,
    /** Fewer than [AdRules.scansPerInterstitial] scans since the last interstitial. */
    TOO_FEW_SCANS,
    /** Less than [AdRules.minGapMillis] since the last interstitial. */
    TOO_SOON,
    /** [AdRules.maxPerDay] interstitials already shown today. */
    DAILY_CAP,
}

/**
 * Interstitial frequency cap (docs/research/business.md §2.3, ui-ux.md §5.2).
 * Tune via remote config later; these are the launch defaults.
 */
data class AdRules(
    val freeScans: Int = 3,
    val scansPerInterstitial: Int = 3,
    val minGapMillis: Long = 3L * 60 * 1000,
    val maxPerDay: Int = 4,
)

/**
 * Decides whether an interstitial may show. Pure Kotlin: no Android or ads SDK types.
 *
 * Rules (all must pass):
 * 1. never on a camera/AR screen, and never when the next action is "Place in my room" or "Share";
 * 2. none during the first 3 completed scans (the 4th saved scan is the earliest);
 * 3. at most 1 per 3 completed scans;
 * 4. at least 3 minutes since the previous interstitial;
 * 5. at most 4 per local calendar day.
 *
 * A clock that moved backwards (user changed the time) counts as "enough time has passed" so a
 * wrong clock can never block ads forever; the per-scan and per-day caps still apply.
 */
class AdPolicy(
    private val store: AdCounterStore,
    private val clock: AdClock = AdClock.System,
    val rules: AdRules = AdRules(),
) {
    /** Call exactly once each time a scan or measurement is successfully saved. */
    suspend fun recordCompletedScan(): AdCounters = store.update {
        it.copy(
            completedScans = it.completedScans.saturatingInc(),
            scansSinceInterstitial = it.scansSinceInterstitial.saturatingInc(),
        )
    }

    /** Call when an interstitial has actually been shown (not merely requested). */
    suspend fun recordInterstitialShown(): AdCounters {
        val now = clock.nowMillis()
        val today = clock.epochDay(now)
        return store.update {
            it.copy(
                scansSinceInterstitial = 0,
                lastInterstitialAtMillis = now,
                interstitialDay = today,
                interstitialsOnDay = if (it.interstitialDay == today) it.interstitialsOnDay + 1 else 1,
            )
        }
    }

    /** Whether an interstitial may show right now for this transition. */
    suspend fun decide(nextAction: NextAction, screen: AdScreen = AdScreen.NON_CAMERA): AdDecision {
        val now = clock.nowMillis()
        return evaluate(store.read(), now, clock.epochDay(now), nextAction, screen, rules)
    }

    /**
     * True when the next saved scan could be eligible, so preloading an ad now is worthwhile.
     * Ignores time-based rules (they may pass by the time the user saves).
     */
    suspend fun nextSaveMayShow(): Boolean {
        val c = store.read()
        val now = clock.nowMillis()
        val shownToday = if (c.interstitialDay == clock.epochDay(now)) c.interstitialsOnDay else 0
        return c.completedScans + 1 > rules.freeScans &&
            c.scansSinceInterstitial + 1 >= rules.scansPerInterstitial &&
            shownToday < rules.maxPerDay
    }

    companion object {
        /** The rules as a pure function of the counters. */
        fun evaluate(
            c: AdCounters,
            nowMillis: Long,
            today: Long,
            nextAction: NextAction,
            screen: AdScreen,
            rules: AdRules = AdRules(),
        ): AdDecision {
            if (screen == AdScreen.CAMERA_AR) return AdDecision.BLOCKED_CAMERA_SCREEN
            if (nextAction == NextAction.PLACE_IN_ROOM || nextAction == NextAction.SHARE) {
                return AdDecision.BLOCKED_NEXT_ACTION
            }
            if (c.completedScans <= rules.freeScans) return AdDecision.GRACE_PERIOD
            if (c.scansSinceInterstitial < rules.scansPerInterstitial) return AdDecision.TOO_FEW_SCANS
            if (c.lastInterstitialAtMillis > 0L) {
                val elapsed = nowMillis - c.lastInterstitialAtMillis
                if (elapsed in 0 until rules.minGapMillis) return AdDecision.TOO_SOON
            }
            val shownToday = if (c.interstitialDay == today) c.interstitialsOnDay else 0
            if (shownToday >= rules.maxPerDay) return AdDecision.DAILY_CAP
            return AdDecision.SHOW
        }

        private fun Int.saturatingInc(): Int = if (this == Int.MAX_VALUE) this else this + 1
    }
}
