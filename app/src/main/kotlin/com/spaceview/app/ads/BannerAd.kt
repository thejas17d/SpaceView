package com.spaceview.app.ads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.spaceview.app.R
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * Inline adaptive banner with a small "Ad" label, for **non-camera screens only** (Gallery,
 * History, Settings, item detail). Never use it on AR/camera screens: the `AdView` WebView costs
 * memory and competes with tracking; leaving the screen destroys it.
 *
 * Renders nothing until consent allows ads and the SDK is ready, or if the ad fails to load
 * (e.g. offline). While loading it reserves [maxHeight] so content above does not move under the
 * user's finger. Place it at the end of scrolling content, at least 16 dp from any button.
 */
@Composable
fun BannerAd(
    modifier: Modifier = Modifier,
    adsManager: AdsManager = AdsManager.get(LocalContext.current),
    maxHeight: Dp = 90.dp,
) {
    val canShowAds by adsManager.canShowAds.collectAsStateWithLifecycle()
    if (!canShowAds) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt()
        if (widthDp < MIN_BANNER_WIDTH_DP) return@BoxWithConstraints
        val context = LocalContext.current
        key(widthDp) {
            var failed by remember { mutableStateOf(false) }
            var loadedHeightDp by remember { mutableIntStateOf(0) }
            val adView = remember {
                AdView(context).apply {
                    setAdSize(AdSize.getInlineAdaptiveBannerAdSize(widthDp, maxHeight.value.toInt()))
                    adUnitId = adsManager.bannerUnitId
                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            loadedHeightDp = adSize?.height ?: 0
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            failed = true
                        }
                    }
                    loadAd(AdRequest.Builder().build())
                }
            }
            LifecycleResumeEffect(adView) {
                adView.resume()
                onPauseOrDispose { adView.pause() }
            }
            DisposableEffect(adView) { onDispose { adView.destroy() } }
            if (!failed) {
                val adLabel = stringResource(R.string.ads_advertisement)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = adLabel },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ads_label),
                        style = SpaceViewTheme.typography.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val height = if (loadedHeightDp > 0) loadedHeightDp.dp else maxHeight
                    AndroidView(
                        factory = { adView },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(height),
                    )
                }
            }
        }
    }
}

private const val MIN_BANNER_WIDTH_DP = 300
