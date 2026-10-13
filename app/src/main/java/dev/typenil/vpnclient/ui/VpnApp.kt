package dev.typenil.vpnclient.ui

import android.app.Activity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import dev.typenil.vpnclient.ui.onboarding.OnboardingGate
import dev.typenil.vpnclient.ui.onboarding.OnboardingGateViewModel
import dev.typenil.vpnclient.ui.onboarding.OnboardingHost
import dev.typenil.vpnclient.ui.onboarding.OnboardingStep
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.common.ThemeMode
import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor.ExtractedImport
import dev.typenil.vpnclient.core.vpn.ConnectionManager
import dev.typenil.vpnclient.data.settings.SettingsRepository
import dev.typenil.vpnclient.ui.appfilter.AppFilterScreen
import dev.typenil.vpnclient.ui.common.headingSemantics
import dev.typenil.vpnclient.ui.connections.ConnectionsScreen
import dev.typenil.vpnclient.ui.diagnostics.DiagnosticsScreen
import dev.typenil.vpnclient.ui.home.HomeScreen
import dev.typenil.vpnclient.ui.qrscan.QR_RESULT_KEY
import dev.typenil.vpnclient.ui.qrscan.QrScanScreen
import dev.typenil.vpnclient.ui.routing.RoutingScreen
import dev.typenil.vpnclient.ui.servers.ServersScreen
import dev.typenil.vpnclient.ui.settings.SettingsScreen
import dev.typenil.vpnclient.ui.subscriptions.SubscriptionsScreen
import dev.typenil.vpnclient.ui.theme.AfterglowSheet
import dev.typenil.vpnclient.ui.theme.AfterglowSheetHeader
import dev.typenil.vpnclient.ui.theme.AfterglowTheme
import dev.typenil.vpnclient.ui.theme.AfterglowTokens
import dev.typenil.vpnclient.ui.theme.DarkAfterglow
import dev.typenil.vpnclient.ui.theme.VPNClientTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SUBSCRIPTIONS = "subscriptions"
    const val SETTINGS = "settings"
    const val ROUTING = "routing"
    const val APP_FILTER = "app_filter"
    const val QR_SCAN = "qr_scan"
    const val CONNECTIONS = "connections"
    const val DIAGNOSTICS = "diagnostics"
}

/**
 * Root composable: theme + NavHost.
 *
 * The VPN consent launcher lives here so it fires from whichever screen is
 * visible when [ConnectionManager.prepareIntent] is posted.
 */
@Composable
fun VpnApp(
    connectionManager: ConnectionManager,
    settings: SettingsRepository,
    importUrl: StateFlow<ExtractedImport?>,
    onImportConsumed: () -> Unit,
) {
    // Theme settings read live from DataStore — a change recomposes the root
    // without an activity restart; the VPN session is untouched either way.
    val themeMode by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
    val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle(initialValue = false)
    VPNClientTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
        val gateViewModel: OnboardingGateViewModel = hiltViewModel()
        val gate by gateViewModel.gate.collectAsStateWithLifecycle()
        // Lives above the gate switch, so it survives Loading flips, rotation and process death.
        var onboardingStep by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }
        var activeSheet by rememberSaveable { mutableStateOf<String?>(null) }
        var openAddDialog by rememberSaveable { mutableStateOf(false) }
        val sheetSaveableState = rememberSaveableStateHolder()

        val pendingImport by importUrl.collectAsStateWithLifecycle()
        // External imports wait for the end of onboarding (the StateFlow keeps
        // them); the sheet host only exists once the NavHost does.
        LaunchedEffect(pendingImport, gate) {
            if (pendingImport != null && gate == OnboardingGate.Done) {
                // External imports must surface immediately, even when a
                // full-screen destination is currently covering the sheet host.
                if (navController.currentDestination?.route !in listOf(Routes.HOME, Routes.SETTINGS)) {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                }
                activeSheet = Routes.SUBSCRIPTIONS
            }
        }

        val prepareIntent by connectionManager.prepareIntent.collectAsStateWithLifecycle()
        val vpnConsentLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                connectionManager.onPermissionResult(result.resultCode == Activity.RESULT_OK)
            }
        // No system VPN dialog before onboarding is done: the disclosure comes first.
        LaunchedEffect(prepareIntent, gate) {
            if (gate == OnboardingGate.Done) prepareIntent?.let { vpnConsentLauncher.launch(it) }
        }

        val currentEntry by navController.currentBackStackEntryAsState()
        // QR_SCAN writes into the hosting HOME/SETTINGS entry before popping.
        // The sheet keeps its rememberSaveable add-form fields across that trip.
        val qrResultFlow = remember(currentEntry) {
            currentEntry?.savedStateHandle?.getStateFlow<String?>(QR_RESULT_KEY, null)
                ?: MutableStateFlow(null)
        }
        val qrResult by qrResultFlow.collectAsStateWithLifecycle()
        val colors = AfterglowTheme.colors
        val darkAfterglow = colors == DarkAfterglow
        val activity = LocalActivity.current
        DisposableEffect(activity, colors) {
            val window = activity?.window
            val previousStatus = window?.statusBarColor
            val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            val previousLightStatus = controller?.isAppearanceLightStatusBars
            val previousLightNavigation = controller?.isAppearanceLightNavigationBars
            if (window != null) {
                window.statusBarColor = colors.paper.toArgb()
                controller?.isAppearanceLightStatusBars = !darkAfterglow
                controller?.isAppearanceLightNavigationBars = !darkAfterglow
            }
            onDispose {
                if (window != null) {
                    if (previousStatus != null) window.statusBarColor = previousStatus
                    if (previousLightStatus != null) controller?.isAppearanceLightStatusBars = previousLightStatus
                    if (previousLightNavigation != null) controller?.isAppearanceLightNavigationBars = previousLightNavigation
                }
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = colors.paper,
            // Route-independent: Home already applies navigationBarsPadding;
            // the other destinations handle their own bottom inset, so a
            // conditional root inset can only double-pad or under-pad.
            contentWindowInsets =
                WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            snackbarHost = {
                SnackbarHost(snackbarHostState, Modifier.navigationBarsPadding()) { data ->
                    Snackbar(data, containerColor = colors.ink, contentColor = colors.paper,
                        actionColor = colors.coralLight, shape = AfterglowTokens.cardShape)
                }
            },
        ) { innerPadding ->
            // Not a route: no back stack into onboarding once it is finished.
            if (gate != OnboardingGate.Done) {
                OnboardingHost(
                    gate = gate,
                    step = onboardingStep,
                    onStepChange = { onboardingStep = it },
                    onFinish = gateViewModel::finish,
                    modifier = Modifier.padding(innerPadding),
                )
                return@Scaffold
            }
            Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(innerPadding),
                enterTransition = {
                    fadeIn(tween(AfterglowTokens.motionStandard)) +
                        slideInHorizontally(tween(AfterglowTokens.motionStandard)) { it / 12 }
                },
                exitTransition = { fadeOut(tween(AfterglowTokens.motionFast)) },
                // Navigation 2.10 has separate predictive specs. Keep both
                // paths identical so gesture release/cancellation cannot switch
                // from the library's scale-out to our crossfade mid-transition.
                popEnterTransition = {
                    fadeIn(tween(AfterglowTokens.motionStandard))
                },
                popExitTransition = {
                    fadeOut(tween(AfterglowTokens.motionStandard))
                },
                predictivePopEnterTransition = {
                    fadeIn(tween(AfterglowTokens.motionStandard))
                },
                predictivePopExitTransition = {
                    fadeOut(tween(AfterglowTokens.motionStandard))
                },
            ) {
                composable(Routes.HOME) {
                    DestinationSurface {
                    HomeScreen(
                        onOpenConnections = { navController.navigate(Routes.CONNECTIONS) },
                        onOpenSubscriptions = { activeSheet = Routes.SUBSCRIPTIONS },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                        onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                        onOpenRouting = { activeSheet = Routes.ROUTING },
                        onOpenServers = { activeSheet = Routes.SERVERS },
                        onAddServer = {
                            openAddDialog = true
                            activeSheet = Routes.SUBSCRIPTIONS
                        },
                    )
                    }
                }
                composable(Routes.SETTINGS) {
                    DestinationSurface {
                    // The header travels with the destination — a root topBar
                    // stayed visible over the incoming screen during back
                    // transitions.
                    Column(Modifier.fillMaxSize()) {
                        // NavHost already carries the top inset — no second
                        // status-bar pad on the destination-local header.
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.common_back), tint = colors.ink)
                            }
                            Text(stringResource(R.string.nav_settings), color = colors.ink,
                                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.headingSemantics().padding(start = 8.dp))
                        }
                        SettingsScreen(
                            onOpenRouting = { activeSheet = Routes.ROUTING },
                            onOpenAppFilter = { navController.navigate(Routes.APP_FILTER) },
                            onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                            snackbarHostState = snackbarHostState,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    }
                }
                composable(Routes.APP_FILTER) {
                    DestinationSurface {
                    AppFilterScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.CONNECTIONS) {
                    DestinationSurface {
                    ConnectionsScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.DIAGNOSTICS) {
                    DestinationSurface {
                    DiagnosticsScreen(onBack = { navController.popBackStack() })
                    }
                }
                composable(Routes.QR_SCAN) {
                    DestinationSurface {
                    QrScanScreen(
                        onBack = { navController.popBackStack() },
                        onResult = { url ->
                            // A decode can complete after the user already
                            // backed out — only deliver while the scan entry
                            // is still on top, else the URL lands on the
                            // wrong back-stack entry and pop() eats one too
                            // many screens.
                            if (navController.currentDestination?.route == Routes.QR_SCAN) {
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set(QR_RESULT_KEY, url)
                                navController.popBackStack()
                            }
                        },
                    )
                    }
                }
            }
            if (currentEntry?.destination?.route == Routes.HOME ||
                currentEntry?.destination?.route == Routes.SETTINGS) {
                activeSheet?.let { sheet ->
                    AfterglowSheet(onDismiss = { activeSheet = null }) {
                        sheetSaveableState.SaveableStateProvider(sheet) {
                        when (sheet) {
                            Routes.SERVERS -> {
                                AfterglowSheetHeader(stringResource(R.string.nav_servers),
                                    stringResource(R.string.common_dismiss), { activeSheet = null })
                                ServersScreen(onAddServer = {
                                    openAddDialog = true
                                    activeSheet = Routes.SUBSCRIPTIONS
                                })
                            }
                            Routes.SUBSCRIPTIONS -> {
                                // A sheet-local host — messages bound to the
                                // root Scaffold host would render behind the
                                // modal sheet and never be seen.
                                val sheetSnackbar = remember { SnackbarHostState() }
                                AfterglowSheetHeader(stringResource(R.string.nav_subscriptions),
                                    stringResource(R.string.common_dismiss), { activeSheet = null })
                                Box(Modifier.fillMaxWidth()) {
                                    SubscriptionsScreen(
                                        snackbarHostState = sheetSnackbar,
                                        import = pendingImport ?: qrResult?.let { ExtractedImport(it, null) },
                                        openAddDialog = openAddDialog,
                                        onAddDialogSignalConsumed = { openAddDialog = false },
                                        onImportConsumed = {
                                            if (pendingImport != null) onImportConsumed()
                                            currentEntry?.savedStateHandle?.remove<String>(QR_RESULT_KEY)
                                        },
                                        onScanQr = { navController.navigate(Routes.QR_SCAN) { launchSingleTop = true } },
                                    )
                                    SnackbarHost(sheetSnackbar, Modifier.align(Alignment.BottomCenter)) { data ->
                                        Snackbar(data, containerColor = colors.ink, contentColor = colors.paper,
                                            actionColor = colors.coralLight, shape = AfterglowTokens.cardShape)
                                    }
                                }
                            }
                            Routes.ROUTING -> {
                                // Own host per sheet — not the shared root one.
                                val routingSnackbar = remember { SnackbarHostState() }
                                RoutingScreen(
                                    onBack = { activeSheet = null },
                                    onOpenAppFilter = { navController.navigate(Routes.APP_FILTER) },
                                    snackbarHostState = routingSnackbar,
                                )
                            }
                        }
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * Opaque paper backing for one NavHost destination. During transitions the
 * outgoing and incoming screens overlap; transparent destinations leaked
 * stale content (headers, heroes) through each other mid-gesture.
 */
@Composable
private fun DestinationSurface(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(AfterglowTheme.colors.paper)) {
        content()
    }
}
