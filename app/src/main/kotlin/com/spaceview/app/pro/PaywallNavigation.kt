package com.spaceview.app.pro

import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import com.spaceview.app.appContainer

/** The paywall route, `paywall/{feature}` (contract K5). */
object PaywallDestination {
    const val ARG_FEATURE = "feature"
    const val ROUTE_PATTERN = "paywall/{$ARG_FEATURE}"

    fun route(feature: ProFeature): String = "paywall/${feature.name}"

    /** Unknown or missing values fall back to [ProFeature.NO_ADS] (the generic pitch). */
    fun parse(arg: String?): ProFeature = ProFeature.entries.firstOrNull { it.name == arg } ?: ProFeature.NO_ADS
}

/**
 * Adds the paywall destination. The orchestrator wires it in `AppNavHost`:
 * `paywallDestination(onClose = { navController.popBackStack() })`.
 *
 * Deliberately no `NonArScreenAdEffect`: no interstitial ever opens on the paywall.
 */
fun NavGraphBuilder.paywallDestination(onClose: () -> Unit) {
    composable(
        PaywallDestination.ROUTE_PATTERN,
        arguments = listOf(navArgument(PaywallDestination.ARG_FEATURE) { type = NavType.StringType }),
    ) { entry ->
        val container = LocalContext.current.appContainer
        val feature = PaywallDestination.parse(entry.arguments?.getString(PaywallDestination.ARG_FEATURE))
        val vm = viewModel { PaywallViewModel(feature, createSavedStateHandle(), container.entitlements) }
        PaywallRoute(viewModel = vm, onClose = onClose)
    }
}

/**
 * Opens the paywall for [feature]. Pass `{ navController.openPaywall(it) }` as `onOpenPaywall` (K5) to
 * screens; never call it from inside an AR session (gate before the session starts, or at Save).
 */
fun NavController.openPaywall(feature: ProFeature) {
    navigate(PaywallDestination.route(feature)) { launchSingleTop = true }
}
