package com.spaceview.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.nav.AppNavHost
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * Single activity. Startup is deliberately light: no ARCore, Filament or AdMob work happens here
 * or in [SpaceViewApplication]. AR is initialised only when the user opens an AR screen.
 *
 * Configuration changes (rotation, size) are handled by Compose without recreating the activity
 * (manifest `configChanges`), so an AR session is not torn down on rotation.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SpaceViewTheme {
                AppNavHost()
            }
        }
        // Consent first, then the Mobile Ads SDK off the main thread, after the first frame.
        AdsManager.get(this).startAfterFirstFrame(this)
    }
}
