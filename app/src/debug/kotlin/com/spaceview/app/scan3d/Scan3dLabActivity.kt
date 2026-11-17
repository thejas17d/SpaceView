package com.spaceview.app.scan3d

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * The 3D Scan Lab (ARCHITECTURE-v2 §7.1.10 device spike; M4-P1 gate §8 D1). **Debug builds only:** this class, its
 * screens and strings live in the `debug` source set and its manifest entry in `src/debug/AndroidManifest.xml`, so a
 * release build contains none of it. Opened from Settings → debug panel (`BuildConfig.DEBUG`).
 *
 * A separate activity (not a nav destination) keeps the lab out of `AppNavHost` and every release code path. No ads
 * run here. Rotation is handled by Compose (manifest `configChanges`), so an AR session or a capture survives it.
 */
class Scan3dLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val factory = viewModelFactory { initializer { LabViewModel(application) } }
        val vm = ViewModelProvider(this, factory)[LabViewModel::class.java]
        setContent {
            SpaceViewTheme {
                Scan3dLabApp(vm, onClose = ::finish)
            }
        }
    }
}
