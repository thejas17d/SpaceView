package com.spaceview.app.pro

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/** The answer of a gate for one new action. */
sealed interface GateState {
    /** No limit applies (Pro, or a feature that is unlimited on Free). */
    data object Allowed : GateState

    /** Allowed, with [remaining] of [limit] left on Free (show "18 / 20"). */
    data class Limited(val remaining: Int, val limit: Int) : GateState

    /** The new action needs Pro. [limit] is the Free allowance (0 = none). Offer the paywall; never lose data. */
    data class NeedsPro(val limit: Int) : GateState

    val allowed: Boolean get() = this !is NeedsPro
}

/**
 * Contract K1 (ARCHITECTURE-v2 §12.3). Features ask this, never billing. Ask **before** a task starts
 * (picker, "Scan", gallery-full check) or at Save, never in the middle of an AR session (ADR-005).
 *
 * A gate only guards *new* actions. Never use it to hide, lock or delete something the user already
 * has: a lapsed Pro user with 40 gallery items still views, places, shares and deletes all 40.
 */
interface FeatureGate {
    /**
     * Live gate state, e.g. for a "18 / 20" counter. [usage] is the current count for [Allowance.Count]
     * features (saved gallery items, saved 3D scans, compared items); ignored otherwise.
     */
    fun observe(feature: ProFeature, usage: Flow<Int> = flowOf(0)): Flow<GateState>

    /**
     * One-shot check before starting a new action. Waits at most ~1 s for a cold-start entitlement,
     * so a Pro user is not shown the paywall in the first second after launch.
     */
    suspend fun check(feature: ProFeature, currentUsage: Int = 0): GateState
}

/** The pure gate table. */
object FeatureRules {
    fun evaluate(feature: ProFeature, entitlement: Entitlement, usage: Int): GateState {
        val rule = ProCatalog.rule(feature)
        val allowance = if (entitlement is Entitlement.Pro) rule.pro else rule.free
        return when (allowance) {
            Allowance.Unlimited -> GateState.Allowed
            Allowance.None -> GateState.NeedsPro(limit = 0)
            is Allowance.Count -> {
                val remaining = allowance.max - usage.coerceAtLeast(0)
                if (remaining > 0) GateState.Limited(remaining, allowance.max) else GateState.NeedsPro(allowance.max)
            }
        }
    }
}

/** [FeatureGate] over the app's entitlement. Unknown, Free and Pending all gate as Free. */
class EntitlementFeatureGate(
    private val entitlement: StateFlow<Entitlement>,
    private val unknownWaitMillis: Long = UNKNOWN_WAIT_MS,
) : FeatureGate {

    override fun observe(feature: ProFeature, usage: Flow<Int>): Flow<GateState> =
        combine(entitlement, usage) { e, n -> FeatureRules.evaluate(feature, e, n) }.distinctUntilChanged()

    override suspend fun check(feature: ProFeature, currentUsage: Int): GateState {
        val e = entitlement.value.takeIf { it !is Entitlement.Unknown }
            ?: withTimeoutOrNull(unknownWaitMillis) { entitlement.first { it !is Entitlement.Unknown } }
            ?: Entitlement.Free
        return FeatureRules.evaluate(feature, e, currentUsage)
    }

    companion object {
        const val UNKNOWN_WAIT_MS = 1_000L
    }
}
