package com.navis.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.navis.companion.ui.components.NavisBottomBar
import com.navis.companion.commands.NavisActionDispatcher
import com.navis.companion.network.HybridVoiceCommand
import com.navis.companion.ui.screens.HistoryScreen
import com.navis.companion.ui.screens.HomeScreen
import com.navis.companion.ui.screens.LiveAssistScreen
import com.navis.companion.ui.screens.NavigationScreen
import com.navis.companion.ui.screens.ReadTextScreen
import com.navis.companion.ui.screens.SceneDescriptionScreen
import com.navis.companion.ui.screens.SettingsScreen
import com.navis.companion.ui.screens.SplashScreen
import com.navis.companion.ui.screens.TurnByTurnScreen
import com.navis.companion.ui.screens.VoiceAssistantScreen
import com.navis.companion.ui.screens.WakeWordScreen
import com.navis.companion.ui.theme.NavisTheme
import com.navis.companion.ui.viewmodel.NavisViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Home : Screen("home")
    object WakeWord : Screen("wake_word")
    object VoiceAssistant : Screen("voice_assistant")
    object LiveAssist : Screen("live_assist")
    object SceneDescription : Screen("scene_description")
    object ReadText : Screen("read_text")
    object Navigate : Screen("navigate")
    object TurnByTurn : Screen("turn_by_turn")
    object History : Screen("history")
    object Settings : Screen("settings")
}

@Composable
fun NavisAppRoot(viewModel: NavisViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val navController = rememberNavController()
    var pendingDestination by remember { mutableStateOf<String?>(null) }
    var pendingVoiceCommand by remember { mutableStateOf<HybridVoiceCommand?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        val destination = pendingDestination
        pendingDestination = null
        if (granted && destination != null) viewModel.startNavigation(destination)
        else if (!granted) viewModel.reportLocationPermissionDenied()
    }
    val startNavigation: (String) -> Unit = { destination ->
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) viewModel.startNavigation(destination)
        else {
            pendingDestination = destination
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

    val actionDispatcher = remember(navController, viewModel) {
        NavisActionDispatcher(
            openCamera = {
                navController.navigate(Screen.LiveAssist.route) { launchSingleTop = true }
            },
            startDetection = {
                navController.navigate(Screen.LiveAssist.route) { launchSingleTop = true }
                viewModel.refreshPerception()
            },
            openSceneDescription = {
                navController.navigate(Screen.SceneDescription.route) { launchSingleTop = true }
            },
            openNavigation = { destination ->
                destination?.takeIf { it.isNotBlank() }?.let(viewModel::setNavigationDestination)
                navController.navigate(Screen.Navigate.route) { launchSingleTop = true }
            },
            openOcr = {
                navController.navigate(Screen.ReadText.route) { launchSingleTop = true }
            },
            openVoiceAssistant = {
                navController.navigate(Screen.VoiceAssistant.route) { launchSingleTop = true }
            },
            openHistory = {
                navController.navigate(Screen.History.route) { launchSingleTop = true }
            },
            goHome = {
                if (!navController.popBackStack(Screen.Home.route, false)) {
                    navController.navigate(Screen.Home.route) { launchSingleTop = true }
                }
            },
            goBack = {
                if (!navController.popBackStack()) navController.navigate(Screen.Home.route) { launchSingleTop = true }
            },
            openSettings = {
                navController.navigate(Screen.Settings.route) { launchSingleTop = true }
            },
            stopSpeaking = viewModel::stopSpeaking,
            repeatResponse = viewModel::repeatResponse,
            stopNavigation = {
                viewModel.stopNavigation()
                if (navController.currentDestination?.route == Screen.TurnByTurn.route) {
                    navController.popBackStack()
                }
            },
            cancelOperation = viewModel::cancelCurrentOperation
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.voiceActionEvents.collect { command ->
            pendingVoiceCommand = null
            if (command.requiresConfirmation) pendingVoiceCommand = command
            else actionDispatcher.execute(command)
        }
    }

    val bottomBarRoutes = listOf(
        Screen.Home.route,
        Screen.LiveAssist.route,
        Screen.Navigate.route,
        Screen.History.route
    )
    val showBottomBar = currentRoute in bottomBarRoutes

    NavisTheme(darkTheme = uiState.isDarkTheme) {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavisBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            if (currentRoute != route) {
                                navController.navigate(route) {
                                    popUpTo(Screen.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Splash.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                // Screen 1: Splash Screen & Onboarding
                composable(Screen.Splash.route) {
                    SplashScreen(
                        onGetStarted = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        }
                    )
                }

                // Screen 2: Home Dashboard
                composable(Screen.Home.route) {
                    HomeScreen(
                        uiState = uiState,
                        onOpenVoiceAssistant = {
                            navController.navigate(Screen.VoiceAssistant.route) { launchSingleTop = true }
                        },
                        onOpenLiveAssist = { navController.navigate(Screen.LiveAssist.route) },
                        onOpenSceneDescription = { navController.navigate(Screen.SceneDescription.route) },
                        onOpenReadText = { navController.navigate(Screen.ReadText.route) },
                        onOpenNavigation = { navController.navigate(Screen.Navigate.route) },
                        onRepeatLastAlert = { viewModel.speakText(uiState.latestHazardMessage, "caution") },
                        onOpenSettings = { navController.navigate(Screen.Settings.route) }
                    )
                }

                // Screen 3: Wake Word ("Hello NAVIS")
                composable(Screen.WakeWord.route) {
                    WakeWordScreen(
                        onDismiss = { navController.popBackStack() },
                        onWakeWordDetected = {
                            viewModel.startListening()
                            navController.navigate(Screen.VoiceAssistant.route) {
                                popUpTo(Screen.WakeWord.route) { inclusive = true }
                            }
                        }
                    )
                }

                // Screens 4 & 5: Conversational Voice Assistant
                composable(Screen.VoiceAssistant.route) {
                    LaunchedEffect(Unit) {
                        viewModel.speakText("Hello! I'm NAVIS. How can I help you today?")
                    }
                    VoiceAssistantScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onMicClick = { viewModel.startListening() },
                        onStopClick = { viewModel.stopSpeaking() },
                        onSuggestedCommand = { cmd -> viewModel.onUserSpeechCaptured(cmd) },
                        onResponseStyleChange = { style -> viewModel.setResponseStyle(style) },
                        onStartNavigation = { dest ->
                            startNavigation(dest)
                            navController.navigate(Screen.TurnByTurn.route)
                        },
                        onOpenSettings = { navController.navigate(Screen.Settings.route) }
                    )
                }

                // Screen 6: Live Assist
                composable(Screen.LiveAssist.route) {
                    LaunchedEffect(Unit) {
                        viewModel.refreshPerception()
                        while (isActive) {
                            viewModel.refreshCameraSnapshot()
                            delay(1200)
                        }
                    }
                    LiveAssistScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onSpeakAlert = { alert -> viewModel.speakText(alert, "critical") }
                    )
                }

                // Screen 7: Scene Description
                composable(Screen.SceneDescription.route) {
                    LaunchedEffect(Unit) { viewModel.describeSurroundings() }
                    SceneDescriptionScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onRepeatDescription = { viewModel.speakText(uiState.sceneDescription) },
                        onMoreDetails = { viewModel.describeSurroundings("Describe visible objects and their positions. State uncertainty clearly.") }
                    )
                }

                // Screen 8: Read Text (OCR)
                composable(Screen.ReadText.route) {
                    LaunchedEffect(Unit) { viewModel.readTextFromCamera() }
                    ReadTextScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onReadAloud = { text -> if (uiState.ocrStatus == "success") viewModel.speakText(text) },
                        onRetake = { viewModel.readTextFromCamera() }
                    )
                }

                // Screen 9: Navigation & Route Preview
                composable(Screen.Navigate.route) {
                    NavigationScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onStartNavigation = { dest ->
                            startNavigation(dest)
                            navController.navigate(Screen.TurnByTurn.route)
                        }
                    )
                }

                // Screen 10: Turn-by-Turn Guidance
                composable(Screen.TurnByTurn.route) {
                    TurnByTurnScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onPauseNavigation = { paused -> viewModel.setNavigationPaused(paused) },
                        onStopNavigation = {
                            viewModel.stopNavigation()
                            navController.popBackStack()
                        }
                    )
                }

                // Screen 12: Activity History
                composable(Screen.History.route) {
                    HistoryScreen(
                        uiState = uiState,
                        onSpeakItem = { text -> viewModel.speakText(text) }
                    )
                }

                // Screen 11: Settings
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onThemeToggle = { isDark -> viewModel.setThemeMode(isDark) },
                        onWakeWordToggle = { enabled -> viewModel.setWakeWordEnabled(enabled) },
                        onUpdateServerUrl = { url -> viewModel.updateServerUrl(url) },
                        onUpdateApiToken = { token -> viewModel.updateApiToken(token) }
                    )
                }
            }

            pendingVoiceCommand?.let { command ->
                AlertDialog(
                    onDismissRequest = { pendingVoiceCommand = null },
                    title = { Text("Start object detection?") },
                    text = { Text("${command.message} The camera will analyze the current scene.") },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingVoiceCommand = null
                            actionDispatcher.execute(command)
                        }) { Text("Start") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingVoiceCommand = null }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}
