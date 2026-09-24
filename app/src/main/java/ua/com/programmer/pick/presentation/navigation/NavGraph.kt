package ua.com.programmer.pick.presentation.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import sealed.BottomNavItem
import ua.com.programmer.pick.presentation.auth.LoginScreen
import ua.com.programmer.pick.presentation.auth.ScanLoginScreen
import ua.com.programmer.pick.presentation.auth.LoginViewModel
import ua.com.programmer.pick.presentation.document.DocumentDetailScreen
import ua.com.programmer.pick.presentation.documents.DocumentsScreen
import ua.com.programmer.pick.presentation.home.HomeScreen
import ua.com.programmer.pick.presentation.home.HomeViewModel
import ua.com.programmer.pick.presentation.pairing.DevicePairingScreen
import ua.com.programmer.pick.presentation.pairing.DevicePairingViewModel
import ua.com.programmer.pick.presentation.debug.DebugJournalScreen
import ua.com.programmer.pick.presentation.profile.ProfileScreen
import ua.com.programmer.pick.presentation.courier.CourierScreen
import ua.com.programmer.pick.presentation.settings.ScannerSettingsScreen
import ua.com.programmer.pick.presentation.settings.ScannerTestScreen
import ua.com.programmer.pick.presentation.settings.SettingsScreen
import ua.com.programmer.pick.presentation.splash.SplashScreen
import ua.com.programmer.pick.presentation.splash.SplashViewModel
import ua.com.programmer.pick.presentation.task.TaskScreen

@Composable
fun PickNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Splash.route
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Determine if bottom nav should be shown
    val showBottomBar = currentRoute in listOf(
        Screen.Home.route,
        Screen.Documents.route,
        Screen.Profile.route
    )

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                PickBottomNavigationBar(navController = navController)
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(paddingValues)
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
                    onClearError = viewModel::clearError,
                    onScanLogin = {
                        navController.navigate(Screen.ScanLogin.route)
                    },
                    onOpenDeviceLink = { enrollQr ->
                        viewModel.onDeviceLinkOpened()
                        navController.navigate(Screen.DevicePairing.create(enrollQr)) {
                            launchSingleTop = true
                        }
                    }
                )
                LifecycleResumeEffect(Unit) {
                    viewModel.setScreenActive(true)
                    onPauseOrDispose { viewModel.setScreenActive(false) }
                }
            }

            composable(
                route = Screen.DevicePairing.route,
                arguments = listOf(navArgument(Screen.ENROLL_QR_ARG) { defaultValue = "" })
            ) {
                val viewModel: DevicePairingViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsState()

                // The poll is what keeps the pairing code claimable, so it runs
                // only while the screen is actually in front of the worker.
                LifecycleResumeEffect(Unit) {
                    viewModel.onResume()
                    onPauseOrDispose { viewModel.onPause() }
                }

                DevicePairingScreen(
                    uiState = uiState,
                    onNavigateBack = { navController.popBackStack() },
                    onSignIn = { navController.popBackStack(Screen.Login.route, inclusive = false) }
                )
            }

            composable(route = Screen.ScanLogin.route) {
                val viewModel: LoginViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsState()

                // A QR login can be refused for the device too.
                LaunchedEffect(uiState.needsDeviceLink) {
                    if (uiState.needsDeviceLink) {
                        viewModel.onDeviceLinkOpened()
                        navController.navigate(Screen.DevicePairing.create()) {
                            popUpTo(Screen.ScanLogin.route) { inclusive = true }
                        }
                    }
                }

                ScanLoginScreen(
                    uiState = uiState,
                    onNavigateBack = { navController.popBackStack() },
                    onLoginSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(route = Screen.Home.route) {
                val viewModel: HomeViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsState()

                LaunchedEffect(Unit) {
                    viewModel.navigateToLogin.collect {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    }
                }

                // Nothing about a task is persisted (D6): the unfinished-task
                // list is re-read from the server every time Home comes up.
                LifecycleResumeEffect(Unit) {
                    viewModel.refreshOpenTasks()
                    onPauseOrDispose {}
                }

                HomeScreen(
                    uiState = uiState,
                    onLogoutClick = {
                        viewModel.logout()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                    onDocumentTypeClick = { docType ->
                        // A guided type is not a document list: it starts a task.
                        if (docType.isGuided) {
                            navController.navigate(Screen.Task.byType(docType.code))
                            return@HomeScreen
                        }
                        viewModel.setSelectedDocumentType(docType.code)
                        navController.navigate(Screen.Documents.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onCourierClick = {
                        navController.navigate(Screen.Courier.route)
                    },
                    onContinueTask = { task ->
                        viewModel.continueTask(task) { route -> navController.navigate(route) }
                    },
                    onCancelTask = { task -> viewModel.cancelTask(task.id) },
                    taskTypeLabel = viewModel::taskTypeLabel
                )
            }

            composable(
                route = Screen.Task.route,
                arguments = listOf(
                    navArgument(Screen.TASK_ID_ARG) { defaultValue = "" },
                    navArgument(Screen.TASK_TYPE_ARG) { defaultValue = "" },
                    navArgument(Screen.DOCUMENT_ID_ARG) { defaultValue = "" }
                )
            ) {
                TaskScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateHome = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                    onNavigateToDocuments = {
                        navController.navigate(Screen.Documents.route) {
                            popUpTo(Screen.Home.route)
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(route = Screen.Documents.route) {
                DocumentsScreen(
                    onNavigateBack = null,
                    onNavigate = { target ->
                        navController.navigate(target)
                    }
                )
            }

            composable(
                route = Screen.DocumentDetail.route,
                arguments = listOf(navArgument(Screen.DOCUMENT_ID_ARG) { defaultValue = "" })
            ) { backStackEntry ->
                val documentId = backStackEntry.arguments?.getString(Screen.DOCUMENT_ID_ARG) ?: ""
                DocumentDetailScreen(
                    documentId = documentId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToTask = { externalId ->
                        navController.navigate(Screen.Task.byDocument(externalId))
                    }
                )
            }

            composable(route = Screen.Courier.route) {
                CourierScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(route = Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onScannerSettingsClick = {
                        navController.navigate(Screen.ScannerSettings.route)
                    }
                )
            }

            composable(route = Screen.ScannerSettings.route) {
                ScannerSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToTest = {
                        navController.navigate(Screen.ScannerTest.route)
                    }
                )
            }

            composable(route = Screen.ScannerTest.route) {
                ScannerTestScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(route = Screen.Profile.route) {
                ProfileScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onScannerSettingsClick = {
                        navController.navigate(Screen.ScannerSettings.route)
                    },
                    onDebugJournalClick = {
                        navController.navigate(Screen.DebugJournal.route)
                    }
                )
            }

            composable(route = Screen.DebugJournal.route) {
                DebugJournalScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
private fun PickBottomNavigationBar(
    navController: NavHostController
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        BottomNavItem.items.forEach { item ->
            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true

            NavigationBarItem(
                icon = {
                    Icon(
                        painter = painterResource(if (selected) item.selectedIcon else item.unselectedIcon),
                        contentDescription = stringResource(item.titleResId)
                    )
                },
                label = {
                    Text(
                        text = stringResource(item.titleResId),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                selected = selected,
                onClick = {
                    navController.navigate(item.route) {
                        // Pop up to the start destination of the graph to
                        // avoid building up a large stack of destinations
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        // Avoid multiple copies of the same destination
                        launchSingleTop = true
                        // Restore state when reselecting a previously selected item
                        restoreState = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
