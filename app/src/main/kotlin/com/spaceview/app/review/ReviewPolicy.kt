package com.spaceview.app.review

/**
 * The in-app review prompt record, persisted ([DataStoreReviewRecordStore]).
 *
 * @property saves successful saves (measurements, scans, furniture), ever.
 * @property lastAskedAtMillis wall-clock time the review flow was last launched; 0 = never.
 */
data class ReviewRecord(val saves: Int = 0, val lastAskedAtMillis: Long = 0L)

/** Storage for [ReviewRecord]. */
interface ReviewRecordStore {
    suspend fun read(): ReviewRecord

    /** Atomically replaces the record with [transform] of the current one; returns the new value. */
    suspend fun update(transform: (ReviewRecord) -> ReviewRecord): ReviewRecord
}

/** Result of [ReviewPolicy.decide]; anything but [ASK] names the rule that blocked it. */
enum class ReviewDecision {
    ASK,

    /** No positive moment (3rd or later save, a Fits verdict) happened recently. */
    NO_MOMENT,

    /** An AR/camera screen is on screen: never ask over the camera. */
    AR_SCREEN,

    /** An interstitial is owed or showing, or one was shown less than [ReviewRules.quietAfterInterstitialMillis] ago. */
    AFTER_INTERSTITIAL,

    /** Asked less than [ReviewRules.minIntervalMillis] ago (or the clock is set before the last ask). */
    TOO_SOON,
}

/** Launch defaults (M5 brief). */
data class ReviewRules(
    /** The Nth successful save (and every later one) is a positive moment. */
    val savesForMoment: Int = 3,
    /** At most one ask per 90 days. */
    val minIntervalMillis: Long = 90L * DAY_MILLIS,
    /** A positive moment counts for this long: a Fits verdict from an hour ago is not a moment now. */
    val momentLifetimeMillis: Long = 30L * 60 * 1000,
    /** Never right after an interstitial. */
    val quietAfterInterstitialMillis: Long = 10L * 60 * 1000,
) {
    companion object {
        const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}

/**
 * When SpaceView may ask for a Play review. Pure: no Android or Play types (JVM-tested).
 *
 * All must hold: a positive moment within [ReviewRules.momentLifetimeMillis]; no AR screen in the foreground; no
 * interstitial owed or showing and none in the last [ReviewRules.quietAfterInterstitialMillis]; and at least
 * [ReviewRules.minIntervalMillis] since the last ask. A clock set **before** the last ask counts as "too soon":
 * unlike the ad cap, a wrong clock may only make us ask less, never more.
 */
object ReviewPolicy {
    fun decide(
        record: ReviewRecord,
        momentAtMillis: Long?,
        nowMillis: Long,
        arInForeground: Boolean,
        adBusy: Boolean,
        lastInterstitialAtMillis: Long,
        rules: ReviewRules = ReviewRules(),
    ): ReviewDecision {
        if (arInForeground) return ReviewDecision.AR_SCREEN
        if (momentAtMillis == null || nowMillis - momentAtMillis !in 0..rules.momentLifetimeMillis) return ReviewDecision.NO_MOMENT
        if (adBusy) return ReviewDecision.AFTER_INTERSTITIAL
        if (lastInterstitialAtMillis > 0L && nowMillis - lastInterstitialAtMillis < rules.quietAfterInterstitialMillis) {
            return ReviewDecision.AFTER_INTERSTITIAL
        }
        if (record.lastAskedAtMillis > 0L && nowMillis - record.lastAskedAtMillis < rules.minIntervalMillis) {
            return ReviewDecision.TOO_SOON
        }
        return ReviewDecision.ASK
    }

    /** True when the save that produced [record] is a positive moment (the 3rd save or later). */
    fun saveIsMoment(record: ReviewRecord, rules: ReviewRules = ReviewRules()): Boolean = record.saves >= rules.savesForMoment
}
