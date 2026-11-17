package com.spaceview.app.furniture

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.appContainer
import com.spaceview.app.pro.GateState
import com.spaceview.app.pro.ProFeature
import com.spaceview.app.scan.ScanViewModel

/** The furniture route, `furniture`. */
object FurnitureDestination {
    const val ROUTE = "furniture"
}

/**
 * Adds the furniture flow (T). Wired in `AppNavHost`:
 * ```
 * furnitureDestination(
 *     navController = navController,
 *     onDone = { navController.navigate(Routes.GALLERY) { popUpTo(Routes.HOME) } },
 *     onPlace = { id -> navController.navigate(Routes.galleryItem(id)) { popUpTo(Routes.HOME) }; navController.openPlace(id) },
 *     onOpenPaywall = { navController.openPaywall(it) },
 * )
 * ```
 * Entry points call [openFurniture] (Home "Furniture", the empty Gallery's "Add furniture").
 *
 * Ads (K2, same rules as the scan flow): the visit is a Measure-style visit keyed by the back-stack entry; saving
 * records one completed scan; leaving (Back, Done) may let the next non-AR screen use a capped chance only after a save;
 * Place never (the visit closes without arming one); the AR step marks itself with `ArScreenAdGuard`.
 *
 * Gating (K1/K5): a Pro template or a full Free gallery opens the paywall from the picker, before any AR, and Save
 * re-checks through `AppContainer.gatedGalleryStore`.
 */
fun NavGraphBuilder.furnitureDestination(
    navController: NavController,
    onDone: () -> Unit,
    onPlace: (Long) -> Unit,
    onOpenPaywall: (ProFeature) -> Unit,
) {
    composable(FurnitureDestination.ROUTE) { entry ->
        val context = LocalContext.current
        val container = context.appContainer
        val ads = AdsManager.get(context)
        val vm = viewModel {
            FurnitureViewModel(
                savedState = createSavedStateHandle(),
                settingsFlow = ScanViewModel.settingsFlow(container.measureSettings, container.appSettings),
                store = GalleryFurnitureStore(container.gatedGalleryStore, container.galleryRepository),
                arcoreVersion = container.arcoreVersion,
                // Exactly once per stored item, from the save itself.
                onSaved = { ads.recordCompletedScan() },
            )
        }
        val templatesGate = remember { container.featureGate.observe(ProFeature.FURNITURE_TEMPLATES) }
        val gate by templatesGate.collectAsStateWithLifecycle(initialValue = null)
        LaunchedEffect(entry.id) { ads.onMeasureEntered(entry.id) }
        // Ignore a second tap while the exit transition runs (it would act on the screen below).
        val isCurrent = { navController.currentBackStackEntry?.id == entry.id }
        FurnitureRoute(
            viewModel = vm,
            proActive = gate == GateState.Allowed,
            // Leaving before saving never arms an ad (nothing was saved this visit).
            onClose = {
                if (isCurrent()) {
                    ads.onMeasureLeaving(entry.id)
                    navController.popBackStack()
                }
            },
            onDone = {
                if (isCurrent()) {
                    ads.onMeasureLeaving(entry.id)
                    onDone()
                }
            },
            // Place: never an ad (M3 gallery review MAJOR 3). The visit closes without arming a chance.
            onPlace = { id ->
                if (isCurrent()) {
                    ads.onVisitLeftWithoutAd(entry.id)
                    onPlace(id)
                }
            },
            onOpenPaywall = onOpenPaywall,
        )
    }
}

/** Opens the furniture flow (its picker). */
fun NavController.openFurniture() {
    navigate(FurnitureDestination.ROUTE) { launchSingleTop = true }
}
