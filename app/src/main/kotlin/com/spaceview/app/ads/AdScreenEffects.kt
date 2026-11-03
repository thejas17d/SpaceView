package com.spaceview.app.ads

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Marks an AR/camera destination for as long as it is composed (including its exit transition).
 * While any is composed, interstitials are neither preloaded nor shown.
 */
@Composable
fun ArScreenAdGuard(adsManager: AdsManager = AdsManager.get(LocalContext.current)) {
    DisposableEffect(adsManager) {
        adsManager.onArScreenEntered()
        onDispose { adsManager.onArScreenLeft() }
    }
}

/**
 * For non-AR destinations (Home, History, Settings). Inside a navigation destination the lifecycle
 * owner is the back-stack entry, which only reaches RESUMED after its enter transition has
 * finished, so a pending after-measure interstitial is never considered mid-transition.
 */
@Composable
fun NonArScreenAdEffect(adsManager: AdsManager = AdsManager.get(LocalContext.current)) {
    val activity = LocalActivity.current
    LifecycleResumeEffect(adsManager, activity) {
        if (activity != null) adsManager.onNonArScreenResumed(activity)
        onPauseOrDispose { adsManager.onNonArScreenPaused() }
    }
}
