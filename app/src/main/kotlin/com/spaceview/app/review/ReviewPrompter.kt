package com.spaceview.app.review

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The seam over the Play In-App Review API, so [ReviewPrompter] is JVM-testable. */
fun interface ReviewLauncher {
    /** Requests and launches the Play review flow. [onDone] runs once, whether or not Play showed anything. */
    fun launch(activity: Activity, onDone: () -> Unit)
}

/**
 * [ReviewLauncher] over Play's In-App Review API (`com.google.android.play:review`). Play decides whether the card
 * actually appears (it has its own quota) and never says, so [ReviewPrompter] counts every launch as an ask.
 * Sideloaded and debug builds normally see nothing (TESTING: use an internal-track build).
 */
class PlayReviewLauncher(context: Context, private val log: (String) -> Unit = {}) : ReviewLauncher {
    private val appContext = context.applicationContext
    private val manager: ReviewManager by lazy { ReviewManagerFactory.create(appContext) }

    override fun launch(activity: Activity, onDone: () -> Unit) {
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful || activity.isFinishing || activity.isDestroyed) {
                request.exception?.let { log("Review flow unavailable: ${it.javaClass.simpleName}") }
                onDone()
                return@addOnCompleteListener
            }
            manager.launchReviewFlow(activity, request.result).addOnCompleteListener { onDone() }
        }
    }
}

/**
 * Asks for a Play review only after a positive moment (the 3rd and every later successful save, or a Fits verdict
 * in Place), never on an AR screen, never with an interstitial owed or just shown, and at most once per 90 days
 * ([ReviewPolicy]). A moment only arms the prompt: the ask happens on the next **non-AR** screen to resume
 * ([com.spaceview.app.ads.NonArScreenAdEffect]), after the ads manager has had its turn on that resume.
 *
 * Main-thread API.
 */
class ReviewPrompter(
    private val store: ReviewRecordStore,
    private val launcher: ReviewLauncher,
    /** True while an AR/camera destination is composed. */
    private val arInForeground: () -> Boolean,
    /** True while an interstitial is owed for the current resume or is on screen. */
    private val adBusy: () -> Boolean,
    /** When the last interstitial was shown (0 = never). */
    private val lastInterstitialAtMillis: suspend () -> Long,
    /** False once the activity is paused, finishing or destroyed. */
    private val isResumed: (Activity) -> Boolean,
    /** Wall clock (epoch ms). */
    private val clock: () -> Long = System::currentTimeMillis,
    main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val rules: ReviewRules = ReviewRules(),
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    /** When the latest positive moment happened. In memory only: a moment does not outlive the process. */
    private var momentAtMillis: Long? = null
    private var asking = false

    /** Call once per successful save (forwarded from the ads manager's completed-save signal). */
    fun onSaveCompleted() {
        scope.launch {
            val record = guarded { store.update { it.copy(saves = if (it.saves == Int.MAX_VALUE) it.saves else it.saves + 1) } }
                ?: return@launch
            if (ReviewPolicy.saveIsMoment(record, rules)) momentAtMillis = clock()
        }
    }

    /** A Fits verdict in Place. Place is an AR screen, so this only arms the prompt. */
    fun onFitsVerdict() {
        momentAtMillis = clock()
    }

    /**
     * Call when a non-AR screen reaches RESUMED, **after** the ads manager's resume handling (so an owed
     * interstitial is already visible through [adBusy]). Launches the Play flow when [ReviewPolicy] allows.
     */
    fun maybeAsk(activity: Activity) {
        if (asking || momentAtMillis == null) return
        scope.launch {
            val record = guarded { store.read() } ?: return@launch
            val lastAd = guarded { lastInterstitialAtMillis() } ?: return@launch
            val now = clock()
            val decision = ReviewPolicy.decide(record, momentAtMillis, now, arInForeground(), adBusy(), lastAd, rules)
            if (decision != ReviewDecision.ASK || asking || !isResumed(activity)) return@launch
            asking = true
            momentAtMillis = null
            // Recorded before launching: Play gives no "shown" signal, so every launch counts toward the 90 days.
            if (guarded { store.update { it.copy(lastAskedAtMillis = now) } } == null) {
                asking = false
                return@launch
            }
            launcher.launch(activity) { asking = false }
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A broken store must never crash a screen; we simply don't ask.
        log("Review record unavailable: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "ReviewPrompter"
    }
}
