package com.spaceview.app.help

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.spaceview.app.about.AboutScreen
import com.spaceview.app.ads.NonArScreenAdEffect

/** Routes for Help and About (M5). */
object HelpRoutes {
    const val HELP = "help"
    const val ABOUT = "about"
}

/**
 * Adds Help / FAQ and About (M5). Both are non-AR, offline screens without banners; they still take part in the
 * after-save ad and review hand-off like every other non-AR screen ([NonArScreenAdEffect]).
 */
fun NavGraphBuilder.helpAndAboutDestinations(navController: NavController) {
    composable(HelpRoutes.HELP) {
        NonArScreenAdEffect()
        HelpScreen(onBack = { navController.popBackStack() })
    }
    composable(HelpRoutes.ABOUT) {
        NonArScreenAdEffect()
        AboutScreen(
            onBack = { navController.popBackStack() },
            onOpenPrivacySummary = { navController.openHelp() },
        )
    }
}

fun NavController.openHelp() = navigate(HelpRoutes.HELP) { launchSingleTop = true }

fun NavController.openAbout() = navigate(HelpRoutes.ABOUT) { launchSingleTop = true }
