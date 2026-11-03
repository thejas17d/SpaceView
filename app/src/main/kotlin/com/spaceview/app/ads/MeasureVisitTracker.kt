package com.spaceview.app.ads

/**
 * Decides **when** an interstitial may even be considered, independently of [AdPolicy]'s caps
 * (which decide **whether**). Pure Kotlin and main-thread only, so it is unit-tested on the JVM.
 *
 * Rules (docs/research/business.md §2.3, ui-ux.md §5.2, M2 review M6/minor 11):
 * - Only leaving a Measure visit in which at least one result was **saved** creates a pending
 *   chance. Leaving without saving (back from the camera-permission or ARCore gate, no tracking,
 *   nothing saved) never does.
 * - The pending chance is used by the next non-AR screen once it is RESUMED, i.e. after the
 *   navigation transition has finished and the AR screen is gone. It is used at most once and
 *   expires after [pendingTtlMillis], so it can never turn into an ad on app return.
 * - While any AR screen is composed, interstitials are neither shown nor preloaded (the WebView
 *   and network work would compete with tracking).
 *
 * A visit is keyed by the navigation back-stack entry id, which survives activity recreation, so a
 * configuration change does not lose or duplicate the "saved this visit" flag.
 */
class MeasureVisitTracker(
    private val clock: () -> Long,
    private val pendingTtlMillis: Long = DEFAULT_PENDING_TTL_MS,
) {
    private var visitKey: String? = null
    private var savedThisVisit = false
    private var pendingAtMillis: Long? = null
    private var arScreens = 0

    /** True while at least one AR/camera screen is composed. */
    val arInForeground: Boolean get() = arScreens > 0

    /** True when a visit is open and something was saved in it. */
    val hasSavedThisVisit: Boolean get() = visitKey != null && savedThisVisit

    /** A Measure destination with this [key] is on screen. Re-entering the same key keeps its state. */
    fun enterMeasure(key: String) {
        if (key != visitKey) {
            visitKey = key
            savedThisVisit = false
        }
        // A new visit supersedes an unused chance from an earlier one.
        pendingAtMillis = null
    }

    /** A measurement was saved. Ignored outside a Measure visit (e.g. on the calibration screen). */
    fun onScanSaved() {
        if (visitKey != null) savedThisVisit = true
    }

    /**
     * The user is leaving the Measure visit [key]. Returns true (and arms a pending chance) only if
     * something was saved during that visit.
     */
    fun leaveMeasure(key: String): Boolean {
        if (key != visitKey) return false
        val eligible = savedThisVisit
        visitKey = null
        savedThisVisit = false
        pendingAtMillis = if (eligible) clock() else null
        return eligible
    }

    /** Takes the pending chance (at most once). False when none, or when it has expired. */
    fun consumePending(): Boolean {
        val at = pendingAtMillis ?: return false
        pendingAtMillis = null
        return clock() - at in 0..pendingTtlMillis
    }

    fun arScreenEntered() {
        arScreens++
    }

    fun arScreenLeft() {
        if (arScreens > 0) arScreens--
    }

    companion object {
        /** Navigation's default transition is 700 ms; this leaves room for a slow device, not for an app switch. */
        const val DEFAULT_PENDING_TTL_MS: Long = 5_000L
    }
}
