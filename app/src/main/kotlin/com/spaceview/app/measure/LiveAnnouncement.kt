package com.spaceview.app.measure

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * When a changing live value (the AR readout, ≤ 10 Hz) may be announced by TalkBack (M5 accessibility pass): once
 * it has **settled** for [SETTLE_MS], or after [GAP_MS] of continuous change, and never sooner than [GAP_MS] after the
 * previous announcement. Pure, so it is JVM-tested.
 */
internal object AnnounceThrottle {
    /** A value unchanged this long is "settled". */
    const val SETTLE_MS = 1_000L

    /** At most one announcement per this long. */
    const val GAP_MS = 2_000L

    /**
     * The time (ms) at which a pending value may be announced. [lastAnnouncedAt] is the previous announcement
     * (Long.MIN_VALUE / 2 for none), [pendingSince] the first unannounced change, [latestChangeAt] the newest one.
     */
    fun dueAt(lastAnnouncedAt: Long, pendingSince: Long, latestChangeAt: Long): Long =
        max(lastAnnouncedAt + GAP_MS, min(latestChangeAt + SETTLE_MS, pendingSince + GAP_MS))

    /**
     * Settle-only (SM1 review MINOR 7): due [SETTLE_MS] after the latest change, never sooner than [GAP_MS] after the
     * previous announcement, and never forced while the value keeps moving (aiming).
     */
    fun dueAtSettled(lastAnnouncedAt: Long, latestChangeAt: Long): Long =
        max(lastAnnouncedAt + GAP_MS, latestChangeAt + SETTLE_MS)
}

/**
 * [text] as it should be exposed to a polite live region: it follows [text] only per [AnnounceThrottle], so TalkBack
 * reads a live measurement when it settles (or every 2 s while it keeps moving) instead of ten times a second.
 */
@Composable
internal fun rememberThrottledAnnouncement(text: String, settleOnly: Boolean = false): String {
    var shown by remember { mutableStateOf(text) }
    var lastAt by remember { mutableLongStateOf(Long.MIN_VALUE / 2) }
    var pendingSince by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(text) {
        if (text == shown) {
            pendingSince = -1L
            return@LaunchedEffect
        }
        val now = SystemClock.uptimeMillis()
        if (pendingSince < 0) pendingSince = now
        val due = if (settleOnly) AnnounceThrottle.dueAtSettled(lastAt, now) else AnnounceThrottle.dueAt(lastAt, pendingSince, now)
        val wait = due - now
        if (wait > 0) delay(wait) // a newer text cancels this and starts over
        shown = text
        lastAt = SystemClock.uptimeMillis()
        pendingSince = -1L
    }
    return shown
}
