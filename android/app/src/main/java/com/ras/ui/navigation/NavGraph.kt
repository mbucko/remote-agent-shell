package com.ras.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ras.ui.pairing.PairingScreen
import com.ras.ui.sessions.CreateSessionScreen
import com.ras.ui.sessions.SessionsScreen
import com.ras.ui.settings.SettingsScreen
import com.ras.ui.terminal.TerminalScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Routes.Pairing.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Routes.Pairing.route) {
            PairingScreen(
                onPaired = {
                    navController.navigate(Routes.Sessions.route) {
                        popUpTo(Routes.Pairing.route) { inclusive = true }
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.Settings.route)
                }
            )
        }

        composable(Routes.Sessions.route) {
            SessionsScreen(
                onSessionClick = { sessionId ->
                    navController.navigate(Routes.Terminal.createRoute(sessionId))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.Settings.route)
                },
                onNavigateToCreateSession = {
                    navController.navigate(Routes.CreateSession.route)
                },
                onNavigateToPairing = {
                    navController.navigate(Routes.Pairing.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CreateSession.route) {
            CreateSessionScreen(
                onNavigateBack = { navController.popBackStack() },
                onSessionCreated = { sessionId ->
                    navController.popBackStack()
                    // Optionally navigate to the new session
                    // navController.navigate(Routes.Terminal.createRoute(sessionId))
                }
            )
        }

        composable(
            route = Routes.Terminal.route,
            arguments = listOf(
                navArgument(NavArgs.SESSION_ID) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString(NavArgs.SESSION_ID) ?: return@composable
            TerminalScreen(
                sessionId = sessionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onDisconnect = {
                    navController.navigate(Routes.Pairing.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
