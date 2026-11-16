package com.spaceview.app.place

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.spaceview.app.appContainer
import com.spaceview.app.pro.ProFeature

/** The Place route, `place/{id}` (contract K3). */
object PlaceDestination {
    const val ARG_ID = "id"
    const val ROUTE_PATTERN = "place/{$ARG_ID}"

    fun route(itemId: Long): String = "place/$itemId"
}

/**
 * Adds the Place destination (M4). The orchestrator wires it in `AppNavHost`:
 * `placeDestination(onBack = { navController.popBackStack() }, onOpenPaywall = { navController.openPaywall(it) })`,
 * and replaces the gallery item's `onPlace` Toast with `navController.navigate(PlaceDestination.route(id))`.
 *
 * Ads (K2, D7): Place is an AR screen (`ArScreenAdGuard` inside [PlaceRoute]); it never enters a Measure visit,
 * never records a completed scan and never arms an after-save chance, so nothing is owed on entry or exit.
 *
 * [onOpenPaywall] (K5): Place and the fit verdict are Free (ARCHITECTURE-v2 §4.3), so M4 never calls it. It is part
 * of the signature so a later gated action (e.g. comparing several items) opens the paywall through navigation,
 * never from inside the AR session.
 */
fun NavGraphBuilder.placeDestination(onBack: () -> Unit, @Suppress("UNUSED_PARAMETER") onOpenPaywall: (ProFeature) -> Unit) {
    composable(
        PlaceDestination.ROUTE_PATTERN,
        arguments = listOf(navArgument(PlaceDestination.ARG_ID) { type = NavType.LongType }),
    ) { entry ->
        val container = LocalContext.current.appContainer
        val id = entry.arguments?.getLong(PlaceDestination.ARG_ID) ?: -1L
        val vm = viewModel {
            PlaceViewModel(
                itemId = id,
                savedState = createSavedStateHandle(),
                source = GalleryPlaceSource(container.galleryRepository),
                unitsFlow = container.appSettings.unitPreference,
            )
        }
        BackHandler(onBack = onBack)
        PlaceRoute(viewModel = vm, onBack = onBack)
    }
}

/** Opens Place for gallery item [itemId]. */
fun NavController.openPlace(itemId: Long) {
    navigate(PlaceDestination.route(itemId)) { launchSingleTop = true }
}
