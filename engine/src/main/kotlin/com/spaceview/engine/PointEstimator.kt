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
 * @property point mean of the inlier samples with the covariance **of the mean** (standard error², m²),
 *   inflated for autocorrelation and small-sample Student-t coverage, plus the localisation term when a
 *   [LocalisationInput] was given (then [UncertainPoint.includesLocalisation] is true).
 * @property sampleCount samples in the window that were analysed.
 * @property inlierCount samples kept after median/MAD outlier rejection.
 * @property spreadRms 3-D RMS distance of inliers from their mean (m), i.e. per-sample scatter.
 * @property effectiveSampleSize independent-sample equivalent after the autocorrelation correction.
 * @property isStable false if too many outliers or too much scatter; the UI should ask the user to hold still.
 * @property localisationSigma isotropic localisation σ (m) included in [point], 0 when none was given.
 */
class PointEstimate(
    val point: UncertainPoint,
    val sampleCount: Int,
    val inlierCount: Int,
    val spreadRms: Double,
    val effectiveSampleSize: Double,
    val isStable: Boolean,
    val localisationSigma: Double = 0.0,
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
 * covariance of the mean = S / n_eff × (t/1.96)², with n_eff from the bias-corrected lag-1 autocorrelation.
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

    /**
     * Robust estimate, or null while fewer than [PointEstimatorConfig.minSamples] samples are buffered.
     *
     * Without [localisation] the covariance is the jitter standard error only and the point is not
     * marked as localised ([UncertaintyModel] then adds the tier floor). With it, the isotropic
     * aim/localisation σ ([LocalisationInput.sigmaMeters]) is added and the point is marked localised.
     */
    fun estimate(localisation: LocalisationInput? = null): PointEstimate? {
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
        // Per-axis "within one MAD" does not hold jointly: with clustered/quantised samples no sample may
        // pass all three axes. Fall back to the per-axis medians (SE of a median ≈ 1.2533·σ/√n), unstable.
        if (m == 0) return medianFallback(n, medX, medY, medZ, sX, sY, sZ, localisation)
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
        val spreadRms = sqrt(lagDen / m)

        var nEff = m.toDouble()
        var dof = max(1, m - 1).toDouble()
        var tInflation = 1.0
        if (config.correctAutocorrelation && m >= 3 && lagDen > 0) {
            // AR frames are strongly correlated (hand tremor, slow VIO wobble). Four corrections (MAJOR-2):
            // 1. the lag-1 estimate from ~30 samples is biased low by ≈ (1 + 4ρ)/n: add it back;
            // 2. cap at MAX_RHO = 0.97 (not 0.9);
            // 3. the sample variance of a correlated series under-states the process variance:
            //    E[Σ(x − x̄)²] ≈ σ²(m − m/n_eff), so divide by that (never by less than m/2);
            // 4. use a Student-t coverage factor for n_eff − 1 degrees of freedom (never fewer than
            //    MIN_T_DOF), folded into the covariance so the displayed K95·σ keeps ≈95% coverage.
            val rhoRaw = lagNum / lagDen
            val rho = (rhoRaw + (1 + 4 * rhoRaw) / m).coerceIn(0.0, MAX_RHO)
            nEff = (m * (1 - rho) / (1 + rho)).coerceIn(1.0, m.toDouble())
            dof = max(m - m / nEff, max(m / 2.0, 1.0))
            val t = Stats.studentT975(max(nEff - 1, MIN_T_DOF))
            tInflation = (t / Uncertainty.K95).let { it * it }
        }
        val sampleCov = Cov3(cxx / dof, cxy / dof, cxz / dof, cyy / dof, cyz / dof, czz / dof)
        val meanCov = sampleCov * (tInflation / nEff)

        val stable = m >= config.minInlierFraction * n && spreadRms <= config.maxSpreadRms
        return PointEstimate(withLocalisation(Vec3(mx, my, mz), meanCov, localisation), n, m, spreadRms, nEff, stable,
            localisation?.sigmaMeters ?: 0.0)
    }

    private fun withLocalisation(p: Vec3, jitterCov: Cov3, loc: LocalisationInput?): UncertainPoint =
        if (loc == null) UncertainPoint(p, jitterCov) else UncertainPoint(p, jitterCov).withLocalisation(loc)

    private fun medianFallback(
        n: Int, medX: Double, medY: Double, medZ: Double, sX: Double, sY: Double, sZ: Double,
        loc: LocalisationInput?,
    ): PointEstimate {
        val f = MEDIAN_SE_FACTOR * MEDIAN_SE_FACTOR / n
        val cov = Cov3(sX * sX * f, 0.0, 0.0, sY * sY * f, 0.0, sZ * sZ * f)
        var ss = 0.0
        for (k in 0 until n) {
            val i = (start + k) % cap
            val dx = xs[i] - medX; val dy = ys[i] - medY; val dz = zs[i] - medZ
            ss += dx * dx + dy * dy + dz * dz
        }
        return PointEstimate(withLocalisation(Vec3(medX, medY, medZ), cov, loc), n, 0, sqrt(ss / n), n.toDouble(),
            false, loc?.sigmaMeters ?: 0.0)
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

        /** Cap on the bias-corrected lag-1 autocorrelation (n_eff ≥ n/66, then ≥ 1). */
        const val MAX_RHO = 0.97

        /** Fewest Student-t degrees of freedom used (t₀.₉₇₅ = 4.30), so n_eff ≈ 1 stays bounded. */
        const val MIN_T_DOF = 2.0

        /** SE of a Gaussian median relative to σ/√n (√(π/2)). */
        const val MEDIAN_SE_FACTOR = 1.2533
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

    private val T_DOF = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 12.0, 15.0, 20.0, 25.0, 30.0, 40.0, 60.0, 120.0)
    private val T_975 = doubleArrayOf(
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.179, 2.131, 2.086, 2.060, 2.042,
        2.021, 2.000, 1.980,
    )

    /**
     * Two-sided 95% Student-t quantile t₀.₉₇₅(ν) for real ν ≥ 1 (table, interpolated linearly in 1/ν;
     * error < 0.01). Tends to 1.96 as ν → ∞; ν < 1 is treated as 1.
     */
    fun studentT975(nu: Double): Double {
        if (!(nu > 1.0)) return T_975[0]
        if (nu >= 120.0) return Uncertainty.K95 + (T_975.last() - Uncertainty.K95) * 120.0 / nu
        var i = 1
        while (T_DOF[i] < nu) i++
        val a = T_DOF[i - 1]; val b = T_DOF[i]
        val f = (1 / nu - 1 / a) / (1 / b - 1 / a)
        return T_975[i - 1] + f * (T_975[i] - T_975[i - 1])
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
