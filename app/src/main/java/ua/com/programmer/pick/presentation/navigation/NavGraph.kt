package ua.com.programmer.pick.presentation.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ua.com.programmer.pick.presentation.auth.LoginScreen
import ua.com.programmer.pick.presentation.auth.LoginViewModel
import ua.com.programmer.pick.presentation.documents.DocumentsScreen
import ua.com.programmer.pick.presentation.home.HomeScreen
import ua.com.programmer.pick.presentation.home.HomeViewModel
import ua.com.programmer.pick.presentation.splash.SplashScreen
import ua.com.programmer.pick.presentation.splash.SplashViewModel
import ua.com.programmer.pick.presentation.document.DocumentDetailScreen
import ua.com.programmer.pick.presentation.settings.SettingsScreen
import ua.com.programmer.pick.presentation.profile.ProfileScreen

@Composable
fun PickNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Splash.route
) {
    val snackbarHostState = SnackbarHostState()

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(route = Screen.Splash.route) {
            val viewModel: SplashViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            SplashScreen(
                uiState = uiState,
                onStart = { viewModel.start() },
                onNavigate = { target ->
                    navController.navigate(target) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(route = Screen.Login.route) {
            val viewModel: LoginViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            LoginScreen(
                uiState = uiState,
                onLoginClick = viewModel::login,
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                hostState = snackbarHostState,
                onClearError = viewModel::clearError
            )
        }

        composable(route = Screen.Home.route) {
            val viewModel: HomeViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            HomeScreen(
                uiState = uiState,
                onLogoutClick = {
                    viewModel.logout()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onDocumentsClick = {
                    navController.navigate(Screen.Documents.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onProfileClick = {
                    navController.navigate(Screen.Profile.route)
                }
            )
        }

        composable(route = Screen.Documents.route) {
            DocumentsScreen(onNavigate = { target ->
                navController.navigate(target)
            })
        }

        composable(route = Screen.DocumentDetail.route, arguments = listOf(navArgument(Screen.DOCUMENT_ID_ARG) { defaultValue = "" })) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString(Screen.DOCUMENT_ID_ARG) ?: ""
            DocumentDetailScreen(documentId = documentId)
        }

        composable(route = Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(route = Screen.Profile.route) {
            ProfileScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
