package com.spaceview.app.measure

import org.junit.Assert.assertEquals
import org.junit.Test

/** M5 a11y: the live AR readout is announced when it settles, at most once every 2 s. */
class AnnounceThrottleTest {
    private val none = Long.MIN_VALUE / 2

    @Test fun aSettledValueIsAnnouncedOneSecondAfterItsLastChange() {
        // Nothing announced yet; one change at t = 10 s: due at 11 s.
        assertEquals(11_000L, AnnounceThrottle.dueAt(none, 10_000, 10_000))
    }

    @Test fun aValueThatKeepsChangingIsAnnouncedEveryTwoSecondsAtMost() {
        // Pending since 10 s, still changing at 11.9 s: due at 12 s (the 2 s cap), not 12.9 s.
        assertEquals(12_000L, AnnounceThrottle.dueAt(none, 10_000, 11_900))
    }

    @Test fun neverSoonerThanTwoSecondsAfterTheLastAnnouncement() {
        // Announced at 10 s; a change at 10.2 s that settles would be due at 11.2 s, but waits until 12 s.
        assertEquals(12_000L, AnnounceThrottle.dueAt(10_000, 10_200, 10_200))
        // Long after the last one, only the settle time counts.
        assertEquals(31_000L, AnnounceThrottle.dueAt(10_000, 30_000, 30_000))
    }
}
