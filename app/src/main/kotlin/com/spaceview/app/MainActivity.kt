package com.spaceview.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.doOnPreDraw
import com.spaceview.app.nav.AppNavHost
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * Single activity. Startup is deliberately light: no ARCore, Filament, billing or AdMob work happens
 * here or in [SpaceViewApplication]. AR is initialised only when the user opens an AR screen; the
 * entitlement (cache, then Play) and ads start just after the first frame.
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
        val container = appContainer
        // Nothing billing- or ads-related is even constructed before the first frame (P1 review MINOR 6).
        // Then: the entitlement (cache, then Play); ads wait until it has settled and never start for Pro
        // (ADR-011, P1 review MAJOR 1).
        window.decorView.doOnPreDraw { view ->
            view.post {
                if (isFinishing || isDestroyed) return@post
                container.onFirstFrame()
                container.ads.start(this)
            }
        }
    }
}
