package com.spaceview.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spaceview.app.ui.home.HomeScreen
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * Single activity. Startup is deliberately light: no ARCore, Filament or AdMob work happens here
 * or in an Application subclass. AR is initialised only when the user opens an AR screen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SpaceViewTheme {
                // Measure mode is wired up in M2.
                HomeScreen(onStartMeasuring = {})
            }
        }
    }
}
