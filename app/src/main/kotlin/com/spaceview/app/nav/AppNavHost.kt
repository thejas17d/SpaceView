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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.spaceview.app.history.HistoryScreen
import com.spaceview.app.history.HistoryViewModel
import com.spaceview.app.measure.MeasureRoute
import com.spaceview.app.measure.MeasureViewModel
import com.spaceview.app.onboarding.OnboardingScreen
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
            val calibration by container.measureSettings.settings.collectAsStateWithLifecycle(initialValue = null)
            HomeScreen(
                calibratedAtMillis = calibration?.calibration?.calibratedAtMillis,
                onMeasure = { navController.navigate(Routes.measure(it)) },
                onCalibrate = { navController.navigate(Routes.CALIBRATE) },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.MEASURE_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_MODE) { type = NavType.StringType }),
        ) { entry ->
            val vm = viewModel { MeasureViewModel(container.measureSettings, container.appSettings, container.history, container.clock) }
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
            val vm = viewModel { MeasureViewModel(container.measureSettings, container.appSettings, container.history, container.clock) }
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
        composable(Routes.SETTINGS) {
            NonArScreenAdEffect(ads)
            SettingsRoute(
                onRecalibrate = { navController.navigate(Routes.CALIBRATE) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
