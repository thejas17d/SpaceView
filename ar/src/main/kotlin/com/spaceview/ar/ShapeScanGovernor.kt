package com.spaceview.ar

/** Why the governor lowered the integration rate or stopped the capture (§7.1.6). */
enum class GovernorEvent { FRAME_TIME, BACKLOG, THERMAL_MODERATE, THERMAL_SEVERE }

/**
 * The 3D-scan integration-rate governor (ARCHITECTURE-v2 §7.1.6). Pure (time comes in as arguments), allocation-free
 * per frame, main thread only.
 *
 * Integration starts at [rates]`[0]` (5 Hz) and steps down to 3 Hz, then 2 Hz, when:
 * - camera frames are being **dropped**: the p90 of the camera-timestamp intervals over the last [WINDOW_NS] exceeds
 *   [DROP_FACTOR] x the locked frame period ([framePeriodNs]; 50 ms at 30 fps). A healthy 30 fps camera delivers
 *   33.3 ms intervals, so a bare "33 ms" threshold would step down on every session (T-D0 review BLOCKER 1);
 * - the fusion worker has a backlog (no free depth slot at the next copy);
 * - the thermal status reaches MODERATE (`PowerManager.THERMAL_STATUS_MODERATE`).
 *
 * It **never steps up** during a scan. Frame-time and backlog step-downs are at most one per [COOLDOWN_NS], so one
 * hiccup cannot drop it to the floor rate; a thermal rise steps down at once. At SEVERE ([THERMAL_SEVERE]) the
 * capture must stop ([stopped]).
 */
class ShapeScanGovernor(private val rates: IntArray = intArrayOf(5, 3, 2)) {
    init {
        require(rates.isNotEmpty() && rates.all { it > 0 }) { "rates must be positive" }
    }

    /** The camera's locked frame period (ns); default 30 fps. Set it from the session's camera config before capture. */
    var framePeriodNs: Long = FRAME_PERIOD_30FPS_NS
        set(value) {
            require(value > 0) { "frame period must be positive" }
            field = value
        }

    /** p90 intervals above this mean dropped frames. */
    val dropThresholdNs: Long get() = (framePeriodNs * DROP_FACTOR).toLong()

    private var level = 0
    private var lastIntegrationNs = Long.MIN_VALUE
    private var lastStepNs = Long.MIN_VALUE
    private var lastEvaluateNs = Long.MIN_VALUE
    private var thermal = 0

    // Frame intervals with their times (ring), and a scratch copy for the percentile.
    private val intervalAt = LongArray(RING)
    private val intervalNs = LongArray(RING)
    private val scratch = LongArray(RING)
    private var ringNext = 0
    private var ringSize = 0

    private val eventKinds = arrayOfNulls<GovernorEvent>(MAX_EVENTS)
    private val eventTimes = LongArray(MAX_EVENTS)
    private val eventRates = IntArray(MAX_EVENTS)

    /** Number of rate changes and stops so far (≤ [MAX_EVENTS] are kept). */
    var eventCount = 0
        private set

    /** The current integration rate (Hz). */
    val rateHz: Int get() = rates[level]

    /** True once the capture must stop (thermal SEVERE). */
    var stopped = false
        private set

    /** Event [i] (0-based, < [eventCount] and < [MAX_EVENTS]): what happened. */
    fun eventKind(i: Int): GovernorEvent = eventKinds[i]!!

    /** Event [i]'s time (the `nowNs` it was reported with). */
    fun eventTimeNs(i: Int): Long = eventTimes[i]

    /** True when an integration is due at [nowNs] at the current rate. */
    fun due(nowNs: Long): Boolean =
        !stopped && (lastIntegrationNs == Long.MIN_VALUE || nowNs - lastIntegrationNs >= 1_000_000_000L / rateHz)

    /** A frame was integrated (handed to the fusion worker) at [nowNs]. */
    fun onIntegrated(nowNs: Long) {
        lastIntegrationNs = nowNs
    }

    /**
     * One new AR frame at [nowNs], [intervalNs] after the previous one (camera timestamps). Evaluates the frame-time
     * rule at most every [EVALUATE_EVERY_NS].
     */
    fun onFrame(nowNs: Long, intervalNs: Long) {
        if (intervalNs > 0) {
            intervalAt[ringNext] = nowNs
            this.intervalNs[ringNext] = intervalNs
            ringNext = (ringNext + 1) % RING
            if (ringSize < RING) ringSize++
        }
        if (lastEvaluateNs != Long.MIN_VALUE && nowNs - lastEvaluateNs < EVALUATE_EVERY_NS) return
        lastEvaluateNs = nowNs
        val p90 = frameTimeP90(nowNs)
        if (p90 > dropThresholdNs) stepDown(nowNs, GovernorEvent.FRAME_TIME, cooled = true)
    }

    /** The fusion worker still held every slot at a copy ([nowNs]). */
    fun onBacklog(nowNs: Long) = stepDown(nowNs, GovernorEvent.BACKLOG, cooled = true)

    /** `PowerManager` thermal status ([status]: 0 none … 6 shutdown) at [nowNs]. */
    fun onThermal(status: Int, nowNs: Long) {
        val previous = thermal
        thermal = status
        if (status >= THERMAL_SEVERE) {
            if (!stopped) {
                stopped = true
                record(GovernorEvent.THERMAL_SEVERE, nowNs)
            }
        } else if (status >= THERMAL_MODERATE && status > previous) {
            stepDown(nowNs, GovernorEvent.THERMAL_MODERATE, cooled = false)
        }
    }

    /** p90 of the frame intervals of the last [WINDOW_NS] before [nowNs]; 0 with fewer than [MIN_SAMPLES]. */
    fun frameTimeP90(nowNs: Long): Long {
        var n = 0
        for (i in 0 until ringSize) {
            if (nowNs - intervalAt[i] <= WINDOW_NS) scratch[n++] = intervalNs[i]
        }
        if (n < MIN_SAMPLES) return 0
        java.util.Arrays.sort(scratch, 0, n)
        return scratch[((n - 1) * 9) / 10]
    }

    private fun stepDown(nowNs: Long, why: GovernorEvent, cooled: Boolean) {
        if (stopped || level == rates.size - 1) return
        if (cooled && lastStepNs != Long.MIN_VALUE && nowNs - lastStepNs < COOLDOWN_NS) return
        level++
        lastStepNs = nowNs
        record(why, nowNs)
    }

    private fun record(why: GovernorEvent, nowNs: Long) {
        if (eventCount < MAX_EVENTS) {
            eventKinds[eventCount] = why
            eventTimes[eventCount] = nowNs
            eventRates[eventCount] = if (stopped) 0 else rateHz
        }
        eventCount++
    }

    /**
     * The time-weighted mean of the governor's integration rate (Hz) between [startNs] and [endNs]: what the rate rules
     * allowed, independent of how many frames the view gates passed (section 7.1.10 criterion 2 is about this rate).
     * After a SEVERE stop the rate counts as 0. Uses the first [MAX_EVENTS] events.
     */
    fun meanRateHz(startNs: Long, endNs: Long): Double {
        if (endNs <= startNs) return rates[0].toDouble()
        var rate = rates[0].toDouble()
        var t = startNs
        var sum = 0.0
        for (i in 0 until minOf(eventCount, MAX_EVENTS)) {
            val at = eventTimes[i].coerceIn(startNs, endNs)
            sum += rate * (at - t)
            t = at
            rate = eventRates[i].toDouble()
        }
        sum += rate * (endNs - t)
        return sum / (endNs - startNs)
    }

    companion object {
        /** Frame-time window (§7.1.6: the last 2 s). */
        const val WINDOW_NS = 2_000_000_000L

        /** The camera's 30 fps period (`SessionSetup` locks TARGET_FPS_30). */
        const val FRAME_PERIOD_30FPS_NS = 33_333_333L

        /** A p90 interval above this many frame periods means frames are being dropped (about 50 ms at 30 fps). */
        const val DROP_FACTOR = 1.5

        /** At most one frame-time / backlog step-down this often. */
        const val COOLDOWN_NS = 2_000_000_000L

        /** The frame-time rule is evaluated at most this often. */
        const val EVALUATE_EVERY_NS = 500_000_000L

        /** Fewer frame intervals than this in the window: no verdict yet. */
        const val MIN_SAMPLES = 20

        /** `PowerManager.THERMAL_STATUS_MODERATE`. */
        const val THERMAL_MODERATE = 2

        /** `PowerManager.THERMAL_STATUS_SEVERE`. */
        const val THERMAL_SEVERE = 3

        private const val RING = 128
        private const val MAX_EVENTS = 16
    }
}
