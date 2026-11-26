package com.spaceview.app.nav

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
import com.spaceview.app.appContainer
import com.spaceview.app.gallery.GalleryItemScreen
import com.spaceview.app.gallery.GalleryItemViewModel
import com.spaceview.app.gallery.GalleryScreen
import com.spaceview.app.gallery.GalleryViewModel
import com.spaceview.app.help.helpAndAboutDestinations
import com.spaceview.app.help.openAbout
import com.spaceview.app.help.openHelp
import com.spaceview.app.history.HistoryScreen
import com.spaceview.app.history.HistoryViewModel
import com.spaceview.app.measure.MeasureRoute
import com.spaceview.app.measure.MeasureViewModel
import com.spaceview.app.onboarding.OnboardingScreen
import com.spaceview.app.place.openPlace
import com.spaceview.app.place.placeDestination
import com.spaceview.app.pro.openPaywall
import com.spaceview.app.pro.paywallDestination
import com.spaceview.app.scan.GalleryScanStore
import com.spaceview.app.scan.ScanRoute
import com.spaceview.app.scan.ScanViewModel
import com.spaceview.app.range.RangefinderRoute
import com.spaceview.app.range.RangefinderViewModel
import com.spaceview.app.scan.imaging.mlKitSubjectSegmenter
import com.spaceview.app.settings.SettingsRoute
import com.spaceview.app.smart.mlKitObjectNamer
import com.spaceview.app.ui.home.HomeScreen
import com.spaceview.ar.MeasureMode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.spaceview.app.pro.ProFeature
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Navigation routes. */
object Routes {
    const val HOME = "home"
    const val ONBOARDING = "onboarding"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CALIBRATE = "calibrate"
    const val GALLERY = "gallery"
    const val SCAN = "scan"
    /** PLAN v3 W4: Distance (rangefinder) mode. An AR screen (ArScreenAdGuard); saves nothing, so no visit tracker. */
    const val DISTANCE = "distance"
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
    val scope = rememberCoroutineScope()
    // Free gallery cap (Builder B, P1 review MAJOR 3): checked before the scan's AR session starts, so the
    // paywall never opens over the camera. Save checks again (ScanViewModel) and keeps the draft.
    var scanCheck by remember { mutableStateOf<Job?>(null) }
    // N2: the check may wait up to ~6 s at cold start (entitlement settle); Home shows "Checking…" meanwhile.
    var scanChecking by remember { mutableStateOf(false) }
    val startScan: () -> Unit = {
        if (scanCheck?.isActive != true) {
            // M4-P1 gate N4: the check suspends (up to ~1 s), so the user may leave Home / Gallery meanwhile (e.g.
            // tap Settings). Navigate only if the screen that asked is still on top.
            val origin = navController.currentBackStackEntry
            scanChecking = true
            scanCheck = scope.launch {
                try {
                    val gate = container.gallerySaveGate.checkItem()
                    if (navController.currentBackStackEntry !== origin) return@launch
                    if (gate.allowed) navController.navigate(Routes.SCAN) else navController.openPaywall(ProFeature.GALLERY_UNLIMITED)
                } finally {
                    scanChecking = false
                }
            }
        }
    }
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
                onScan = startScan,
                scanChecking = scanChecking,
                onGallery = { navController.navigate(Routes.GALLERY) },
                onDistance = { navController.navigate(Routes.DISTANCE) },
            )
        }
        composable(Routes.DISTANCE) {
            // An AR screen: no ad or consent form may open over it (PLAN v3 review MAJOR 1). It saves nothing, so no
            // visit tracker.
            ArScreenAdGuard(ads)
            val vm = viewModel { RangefinderViewModel(container.measureSettings, container.appSettings) }
            RangefinderRoute(onBack = { navController.popBackStack() }, viewModel = vm)
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
                    // Save backstop for the Free gallery cap (keeps the draft when full).
                    canSave = { container.gallerySaveGate.checkItem().allowed },
                    // SM1b (Builder A): a name chip at Review from on-device ML Kit labelling (K6).
                    namer = mlKitObjectNamer(context),
                    // PLAN v3 W2 (Builder B): on-device background removal for Box / Other (ML Kit, Play services).
                    segmenter = mlKitSubjectSegmenter(context),
                    // PLAN v3.1 W8 (Builder B): the Review's camera photo survives process death (cache, not Room).
                    photoStore = com.spaceview.app.scan.FileReviewPhotoStore(java.io.File(context.cacheDir, com.spaceview.data.gallery.GalleryRepository.SCAN_REVIEW_DIR)),
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
                        // Item screen underneath, so Back from Place returns to the saved item (M4).
                        navController.navigate(Routes.galleryItem(id)) { popUpTo(Routes.HOME) }
                        navController.openPlace(id)
                    }
                },
                // Gallery full at Save (Review is a non-AR step): the scan's draft stays in its view model.
                onOpenPaywall = { navController.openPaywall(it) },
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
                onScan = startScan,
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
                onPlace = { navController.openPlace(id) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            NonArScreenAdEffect(ads)
            SettingsRoute(
                onRecalibrate = { navController.navigate(Routes.CALIBRATE) },
                onBack = { navController.popBackStack() },
                onOpenPaywall = { navController.openPaywall(it) },
                onOpenHelp = { navController.openHelp() },
                onOpenAbout = { navController.openAbout() },
            )
        }
        // M5 remainder (Builder B): Help / FAQ and About, from Settings.
        helpAndAboutDestinations(navController)
        // M4 Place in room (Builder A): AR screen with its own ArScreenAdGuard; Free (K2/K5/D7).
        placeDestination(
            onBack = { navController.popBackStack() },
            onOpenPaywall = { navController.openPaywall(it) },
        )
        // Pro paywall (Builder B): no ad effect on this screen.
        paywallDestination(onClose = { navController.popBackStack() })
    }
}
