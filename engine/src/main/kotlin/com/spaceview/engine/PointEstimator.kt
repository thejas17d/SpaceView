package com.spaceview.engine

import kotlin.math.max
import kotlin.math.sqrt

/** Tracking quality of the AR frame a sample came from (maps to ARCore `TrackingState` + failure reason). */
enum class TrackingQuality {
    /** Full tracking, no failure reason: samples are usable. */
    TRACKING,

    /** Tracking limited (fast motion, low light, few features): samples are discarded. */
    LIMITED,

    /** Tracking lost: samples are discarded and, by default, the buffer is cleared (the map may jump on relocalisation). */
    NOT_TRACKING,
}

/** Outcome of [PointEstimator.add]. */
enum class SampleResult { ACCEPTED, REJECTED_TRACKING, REJECTED_INVALID }

/**
 * Tunables for [PointEstimator].
 *
 * @property minSamples accepted samples needed before an estimate is produced (research: 15–30).
 * @property windowSize most recent samples kept (sliding window).
 * @property outlierRadius robust normalised radius (in MAD-derived σ units, all three axes combined)
 *   above which a sample is an outlier. Default 5: for clean Gaussian data P(χ²₃ > 25) ≈ 1e-5, and it
 *   still leaves margin when the MAD of only ~30 samples underestimates σ by 20–30%. Gross outliers
 *   (hit-test slipping onto the background) are tens of σ away.
 * @property minRobustSigma floor (m) for the per-axis robust σ, so quantised / near-identical samples
 *   do not make the outlier test infinitely strict.
 * @property minInlierFraction below this inlier share the estimate is flagged unstable.
 * @property maxSpreadRms 3-D RMS spread (m) of inliers above which the estimate is flagged unstable (hand shake / sliding hit).
 * @property correctAutocorrelation inflate the standard error using the lag-1 autocorrelation of the samples
 *   (consecutive AR frames are not independent, so σ/√n would be over-confident).
 * @property resetOnTrackingLoss clear the buffer when a [TrackingQuality.NOT_TRACKING] sample arrives.
 */
class PointEstimatorConfig(
    val minSamples: Int = 15,
    val windowSize: Int = 30,
    val outlierRadius: Double = 5.0,
    val minRobustSigma: Double = 0.0005,
    val minInlierFraction: Double = 0.6,
    val maxSpreadRms: Double = 0.02,
    val correctAutocorrelation: Boolean = true,
    val resetOnTrackingLoss: Boolean = true,
) {
    init {
        require(minSamples >= 3) { "minSamples must be >= 3" }
        require(windowSize >= minSamples) { "windowSize must be >= minSamples" }
        require(outlierRadius > 0 && minRobustSigma > 0 && maxSpreadRms > 0)
        require(minInlierFraction in 0.0..1.0)
    }
}

/**
 * Robust estimate of one 3-D point.
 *
 * @property point mean of the inlier samples with the covariance **of the mean** (standard error², m²).
 * @property sampleCount samples in the window that were analysed.
 * @property inlierCount samples kept after median/MAD outlier rejection.
 * @property spreadRms 3-D RMS distance of inliers from their mean (m), i.e. per-sample scatter.
 * @property effectiveSampleSize independent-sample equivalent after the autocorrelation correction.
 * @property isStable false if too many outliers or too much scatter; the UI should ask the user to hold still.
 */
class PointEstimate(
    val point: UncertainPoint,
    val sampleCount: Int,
    val inlierCount: Int,
    val spreadRms: Double,
    val effectiveSampleSize: Double,
    val isStable: Boolean,
) {
    /** Estimated position (m). */
    val position: Vec3 get() = point.position

    /** 3-D standard error of the position, √trace(cov) (m). */
    val standardError: Double get() = sqrt(point.covariance.trace.coerceAtLeast(0.0))

    override fun toString(): String =
        "PointEstimate(pos=$position, se=$standardError, n=$sampleCount, inliers=$inlierCount, stable=$isStable)"
}

/**
 * Accumulates noisy samples of one endpoint (e.g. hit-test results while the reticle is held still)
 * and produces a robust mean with a standard error.
 *
 * Pipeline: tracking gate → sliding window → per-axis median and MAD → reject samples whose robust
 * normalised radius exceeds [PointEstimatorConfig.outlierRadius] → mean and covariance of inliers →
 * covariance of the mean = S / n_eff, with n_eff from lag-1 autocorrelation.
 *
 * Not thread-safe; feed it from one thread. Storage is preallocated (no per-sample allocation).
 */
class PointEstimator(val config: PointEstimatorConfig = PointEstimatorConfig()) {

    private val cap = config.windowSize
    private val xs = DoubleArray(cap)
    private val ys = DoubleArray(cap)
    private val zs = DoubleArray(cap)
    private var start = 0
    private var count = 0

    // Scratch buffers reused by estimate().
    private val scratch = DoubleArray(cap)
    private val inlier = BooleanArray(cap)

    /** Number of accepted samples currently in the window. */
    val sampleCount: Int get() = count

    /** True once enough samples are buffered for [estimate] to return a value. */
    val hasEnoughSamples: Boolean get() = count >= config.minSamples

    /** Fraction of [PointEstimatorConfig.minSamples] gathered, 0..1 (for a progress ring). */
    val progress: Double get() = (count.toDouble() / config.minSamples).coerceAtMost(1.0)

    /** Adds one sample. Only [TrackingQuality.TRACKING] samples with finite coordinates are kept. */
    fun add(position: Vec3, tracking: TrackingQuality = TrackingQuality.TRACKING): SampleResult {
        if (tracking != TrackingQuality.TRACKING) {
            if (tracking == TrackingQuality.NOT_TRACKING && config.resetOnTrackingLoss) reset()
            return SampleResult.REJECTED_TRACKING
        }
        if (!position.isFinite) return SampleResult.REJECTED_INVALID
        val idx: Int
        if (count < cap) {
            idx = (start + count) % cap
            count++
        } else {
            idx = start
            start = (start + 1) % cap
        }
        xs[idx] = position.x; ys[idx] = position.y; zs[idx] = position.z
        return SampleResult.ACCEPTED
    }

    /** Discards all samples. */
    fun reset() {
        start = 0
        count = 0
    }

    /** Robust estimate, or null while fewer than [PointEstimatorConfig.minSamples] samples are buffered. */
    fun estimate(): PointEstimate? {
        if (count < config.minSamples) return null
        val n = count

        val medX = medianOf(xs); val medY = medianOf(ys); val medZ = medianOf(zs)
        val sX = robustSigma(xs, medX); val sY = robustSigma(ys, medY); val sZ = robustSigma(zs, medZ)

        val r2max = config.outlierRadius * config.outlierRadius
        var m = 0
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (k in 0 until n) {
            val i = (start + k) % cap
            val dx = (xs[i] - medX) / sX; val dy = (ys[i] - medY) / sY; val dz = (zs[i] - medZ) / sZ
            val ok = dx * dx + dy * dy + dz * dz <= r2max
            inlier[k] = ok
            if (ok) { m++; sx += xs[i]; sy += ys[i]; sz += zs[i] }
        }
        // m >= 1 always: at least half the samples lie within 1 MAD of the median on each axis.
        val mx = sx / m; val my = sy / m; val mz = sz / m

        var cxx = 0.0; var cxy = 0.0; var cxz = 0.0; var cyy = 0.0; var cyz = 0.0; var czz = 0.0
        var lagNum = 0.0; var lagDen = 0.0
        var hasPrev = false
        var px = 0.0; var py = 0.0; var pz = 0.0
        for (k in 0 until n) {
            if (!inlier[k]) continue
            val i = (start + k) % cap
            val dx = xs[i] - mx; val dy = ys[i] - my; val dz = zs[i] - mz
            cxx += dx * dx; cxy += dx * dy; cxz += dx * dz; cyy += dy * dy; cyz += dy * dz; czz += dz * dz
            lagDen += dx * dx + dy * dy + dz * dz
            if (hasPrev) lagNum += dx * px + dy * py + dz * pz
            px = dx; py = dy; pz = dz; hasPrev = true
        }
        val dof = max(1, m - 1).toDouble()
        val sampleCov = Cov3(cxx / dof, cxy / dof, cxz / dof, cyy / dof, cyz / dof, czz / dof)
        val spreadRms = sqrt(lagDen / m)

        var nEff = m.toDouble()
        if (config.correctAutocorrelation && m >= 3 && lagDen > 0) {
            val rho = (lagNum / lagDen).coerceIn(0.0, MAX_RHO)
            nEff = max(1.0, m * (1 - rho) / (1 + rho))
        }
        val meanCov = sampleCov * (1.0 / nEff)

        val stable = m >= config.minInlierFraction * n && spreadRms <= config.maxSpreadRms
        return PointEstimate(UncertainPoint(Vec3(mx, my, mz), meanCov), n, m, spreadRms, nEff, stable)
    }

    private fun medianOf(src: DoubleArray): Double {
        for (k in 0 until count) scratch[k] = src[(start + k) % cap]
        return Stats.medianInPlace(scratch, count)
    }

    private fun robustSigma(src: DoubleArray, median: Double): Double {
        for (k in 0 until count) scratch[k] = kotlin.math.abs(src[(start + k) % cap] - median)
        val mad = Stats.medianInPlace(scratch, count)
        return max(MAD_TO_SIGMA * mad, config.minRobustSigma)
    }

    private companion object {
        /** MAD → σ for a Gaussian. */
        const val MAD_TO_SIGMA = 1.4826

        /** Cap on the lag-1 autocorrelation (n_eff ≥ n/19) to keep the correction bounded. */
        const val MAX_RHO = 0.9
    }
}

/** Small allocation-free statistics helpers. */
object Stats {
    /**
     * Median of the first [n] values of [a]; **reorders** those values (quickselect).
     * For even n returns the mean of the two middle values.
     */
    fun medianInPlace(a: DoubleArray, n: Int): Double {
        require(n in 1..a.size) { "n out of range" }
        val mid = n / 2
        val hi = select(a, n, mid)
        if (n % 2 == 1) return hi
        // After select, elements [0, mid) are <= a[mid]; the lower middle is their maximum.
        var lo = a[0]
        for (i in 1 until mid) if (a[i] > lo) lo = a[i]
        return (lo + hi) / 2
    }

    /** Median of a list (does not modify it). */
    fun median(values: List<Double>): Double {
        val a = values.toDoubleArray()
        return medianInPlace(a, a.size)
    }

    /** k-th smallest (0-based) of a[0 until n] via Hoare quickselect; partially reorders [a]. */
    private fun select(a: DoubleArray, n: Int, k: Int): Double {
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val pivot = a[(lo + hi) ushr 1]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    val t = a[i]; a[i] = a[j]; a[j] = t
                    i++; j--
                }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
        }
        return a[k]
    }
}
