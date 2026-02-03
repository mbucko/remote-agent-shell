package com.ras.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ras.ui.connecting.ConnectingScreen
import com.ras.ui.home.HomeScreen
import com.ras.ui.pairing.PairingScreen
import com.ras.ui.sessions.CreateSessionScreen
import com.ras.ui.sessions.SessionsScreen
import com.ras.ui.settings.SettingsScreen
import com.ras.ui.terminal.TerminalScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Routes.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Routes.Home.route) {
            // Check for showDisconnectedMessage flag from previous navigation
            val showDisconnectedMessage = it.savedStateHandle.get<Boolean>("showDisconnectedMessage") ?: false
            // Clear the flag after reading
            it.savedStateHandle.remove<Boolean>("showDisconnectedMessage")

            HomeScreen(
                onNavigateToConnecting = { deviceId ->
                    navController.navigate(Routes.Connecting.createRoute(deviceId))
                },
                onNavigateToPairing = {
                    navController.navigate(Routes.Pairing.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.Settings.route)
                },
                onNavigateToSessions = { deviceId ->
                    navController.navigate(Routes.Sessions.createRoute(deviceId)) {
                        popUpTo(Routes.Home.route) { inclusive = false }
                    }
                },
                showDisconnectedMessage = showDisconnectedMessage
            )
        }

        composable(
            route = Routes.Connecting.route,
            arguments = listOf(
                navArgument(NavArgs.DEVICE_ID) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString(NavArgs.DEVICE_ID) ?: return@composable
            ConnectingScreen(
                deviceId = deviceId,
                onNavigateToSessions = { devId ->
                    navController.navigate(Routes.Sessions.createRoute(devId)) {
                        popUpTo(Routes.Home.route) { inclusive = false }
                    }
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.Pairing.route) {
            PairingScreen(
                onPaired = {
                    navController.navigate(Routes.Home.route) {
                        popUpTo(Routes.Pairing.route) { inclusive = true }
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.Settings.route)
                }
            )
        }

        composable(
            route = Routes.Sessions.route,
            arguments = listOf(
                navArgument(NavArgs.DEVICE_ID) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString(NavArgs.DEVICE_ID) ?: return@composable
            SessionsScreen(
                deviceId = deviceId,
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
                        popUpTo(Routes.Home.route) { inclusive = false }
                    }
                },
                onDisconnect = {
                    // Navigate to Home with snackbar message
                    navController.navigate(Routes.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                    navController.currentBackStackEntry?.savedStateHandle?.set("showDisconnectedMessage", true)
                }
            )
        }

        composable(Routes.CreateSession.route) {
            CreateSessionScreen(
                onNavigateBack = { navController.popBackStack() },
                onSessionCreated = { sessionId ->
                    // Navigate to terminal, removing CreateSession from back stack
                    navController.navigate(Routes.Terminal.createRoute(sessionId)) {
                        popUpTo(Routes.Sessions.route) { inclusive = false }
                    }
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
                    // Navigate to Home with snackbar message
                    navController.navigate(Routes.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                    navController.currentBackStackEntry?.savedStateHandle?.set("showDisconnectedMessage", true)
                }
            )
        }
    }
}
