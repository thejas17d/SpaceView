package com.spaceview.app.nav

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.ads.ArScreenAdGuard
import com.spaceview.app.ads.NonArScreenAdEffect
import com.spaceview.app.R
import com.spaceview.app.appContainer
import com.spaceview.app.gallery.GalleryItemScreen
import com.spaceview.app.gallery.GalleryItemViewModel
import com.spaceview.app.gallery.GalleryScreen
import com.spaceview.app.gallery.GalleryViewModel
import com.spaceview.app.history.HistoryScreen
import com.spaceview.app.history.HistoryViewModel
import com.spaceview.app.measure.MeasureRoute
import com.spaceview.app.measure.MeasureViewModel
import com.spaceview.app.onboarding.OnboardingScreen
import com.spaceview.app.scan.GalleryScanStore
import com.spaceview.app.scan.ScanRoute
import com.spaceview.app.scan.ScanViewModel
import com.spaceview.app.settings.SettingsRoute
import com.spaceview.app.ui.home.HomeScreen
import com.spaceview.ar.MeasureMode
import kotlinx.coroutines.flow.first

/** Navigation routes. */
object Routes {
    const val HOME = "home"
    const val ONBOARDING = "onboarding"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CALIBRATE = "calibrate"
    const val GALLERY = "gallery"
    const val SCAN = "scan"
    private const val GALLERY_ITEM = "gallery_item"
    const val ARG_ID = "id"
    const val GALLERY_ITEM_PATTERN = "$GALLERY_ITEM/{$ARG_ID}"

    fun galleryItem(id: Long) = "$GALLERY_ITEM/$id"
    private const val MEASURE = "measure"
    const val MEASURE_PATTERN = "$MEASURE/{mode}"
    const val ARG_MODE = "mode"

    fun measure(mode: MeasureMode) = "$MEASURE/${mode.name}"

    /** Parses the mode argument; unknown values fall back to distance. */
    fun parseMode(arg: String?): MeasureMode = MeasureMode.entries.firstOrNull { it.name == arg } ?: MeasureMode.DISTANCE
}

/**
 * The app's navigation host. Onboarding runs once (state from the settings DataStore); AR screens
 * are full-bleed destinations whose ARCore session lives exactly as long as the destination.
 */
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val container = context.appContainer
    val ads = AdsManager.get(context)
    // Wait for the first DataStore answer so we never flash the wrong start screen.
    val onboardingDone by produceState<Boolean?>(initialValue = null) {
        value = container.appSettings.onboardingCompleted.first()
    }
    val done = onboardingDone
    if (done == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    NavHost(navController = navController, startDestination = if (done) Routes.HOME else Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onDone = { navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } },
                onCalibrateNow = {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    navController.navigate(Routes.CALIBRATE)
                },
            )
        }
        composable(Routes.HOME) {
            NonArScreenAdEffect(ads)
            HomeScreen(
                onMeasure = { navController.navigate(Routes.measure(it)) },
                onCalibrate = { navController.navigate(Routes.CALIBRATE) },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onScan = { navController.navigate(Routes.SCAN) },
                onGallery = { navController.navigate(Routes.GALLERY) },
            )
        }
        composable(Routes.SCAN) { entry ->
            val vm = viewModel {
                ScanViewModel(
                    savedState = createSavedStateHandle(),
                    settingsFlow = ScanViewModel.settingsFlow(container.measureSettings, container.appSettings),
                    store = GalleryScanStore(container.galleryRepository),
                    arcoreVersion = container.arcoreVersion,
                    // Exactly once per stored scan, from the save itself (never lost to a missed event).
                    onScanSaved = { ads.recordCompletedScan() },
                )
            }
            // A scan visit is a Measure-style visit: an interstitial is only possible after a save, and only
            // once the next non-AR screen has resumed (never over the camera). The AR steps inside the route
            // mark themselves with ArScreenAdGuard.
            LaunchedEffect(entry.id) { ads.onMeasureEntered(entry.id) }
            val isCurrent = { navController.currentBackStackEntry?.id == entry.id }
            ScanRoute(
                viewModel = vm,
                // Leaving before saving never arms an ad (nothing was saved this visit).
                onClose = {
                    if (isCurrent()) {
                        ads.onMeasureLeaving(entry.id)
                        navController.popBackStack()
                    }
                },
                // Done on Saved (Back uses onClose): the end of a content segment, an ad slot (ui-ux §5.2).
                onDone = {
                    if (isCurrent()) {
                        ads.onMeasureLeaving(entry.id)
                        navController.navigate(Routes.GALLERY) { popUpTo(Routes.HOME) }
                    }
                },
                // Place: never an ad (M3 gallery review MAJOR 3). The visit closes without arming a chance.
                onPlace = { id ->
                    if (isCurrent()) {
                        ads.onVisitLeftWithoutAd(entry.id)
                        navController.navigate(Routes.galleryItem(id)) { popUpTo(Routes.HOME) }
                    }
                },
            )
        }
        composable(
            Routes.MEASURE_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_MODE) { type = NavType.StringType }),
        ) { entry ->
            val vm = viewModel { MeasureViewModel(container.measureSettings, container.appSettings, container.history, container.clock, container.arcoreVersion) }
            ArScreenAdGuard(ads)
            // One Measure visit per back-stack entry (the id survives activity recreation).
            LaunchedEffect(entry.id) { ads.onMeasureEntered(entry.id) }
            // Leaving arms an after-measure interstitial chance only if something was saved during
            // this visit. The ad is never requested here: Home asks for it once it is RESUMED, i.e.
            // after the transition, with the AR screen gone (AdsManager.onNonArScreenResumed).
            val close: () -> Unit = {
                // Ignore a second tap while the exit transition runs (it would pop Home too).
                if (navController.currentBackStackEntry?.id == entry.id) {
                    ads.onMeasureLeaving(entry.id)
                    navController.popBackStack()
                }
            }
            BackHandler(onBack = close)
            MeasureRoute(
                initialMode = Routes.parseMode(entry.arguments?.getString(Routes.ARG_MODE)),
                startWithCalibration = false,
                viewModel = vm,
                onClose = close,
            )
        }
        composable(Routes.CALIBRATE) {
            val vm = viewModel { MeasureViewModel(container.measureSettings, container.appSettings, container.history, container.clock, container.arcoreVersion) }
            ArScreenAdGuard(ads)
            MeasureRoute(
                initialMode = MeasureMode.DISTANCE,
                startWithCalibration = true,
                viewModel = vm,
                onClose = { navController.popBackStack() },
            )
        }
        composable(Routes.HISTORY) {
            NonArScreenAdEffect(ads)
            val vm = viewModel { HistoryViewModel(container.history, container.appSettings) }
            HistoryScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.GALLERY) {
            NonArScreenAdEffect(ads)
            val vm = viewModel { GalleryViewModel(container.galleryRepository, container.appSettings) }
            GalleryScreen(
                viewModel = vm,
                onOpen = { navController.navigate(Routes.galleryItem(it)) },
                onBack = { navController.popBackStack() },
                onScan = { navController.navigate(Routes.SCAN) },
            )
        }
        composable(
            Routes.GALLERY_ITEM_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType }),
        ) { entry ->
            NonArScreenAdEffect(ads)
            val id = entry.arguments?.getLong(Routes.ARG_ID) ?: -1L
            val vm = viewModel { GalleryItemViewModel(id, container.galleryRepository, container.appSettings) }
            GalleryItemScreen(
                viewModel = vm,
                // M4 replaces this with the placement AR screen.
                onPlace = { Toast.makeText(context, R.string.gallery_place_soon, Toast.LENGTH_SHORT).show() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            NonArScreenAdEffect(ads)
            SettingsRoute(
                onRecalibrate = { navController.navigate(Routes.CALIBRATE) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
