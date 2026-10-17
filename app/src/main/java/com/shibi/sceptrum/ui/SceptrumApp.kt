package com.shibi.sceptrum.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.merge
import com.shibi.sceptrum.appGraph
import com.shibi.sceptrum.appearance.AppearanceViewModel
import com.shibi.sceptrum.devices.DevicesUiState
import com.shibi.sceptrum.devices.DevicesViewModel
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.LanAccessState
import com.shibi.sceptrum.domain.PairingUiState
import com.shibi.sceptrum.domain.WindowWidthClass
import com.shibi.sceptrum.domain.shownWith
import com.shibi.sceptrum.ui.background.AppBackgroundHost
import com.shibi.sceptrum.pairing.PairingViewModel
import com.shibi.sceptrum.remote.AppsViewModel
import com.shibi.sceptrum.remote.KeyboardViewModel
import com.shibi.sceptrum.remote.RemoteHaptic
import com.shibi.sceptrum.remote.RemoteViewModel
import com.shibi.sceptrum.settings.RemoteSettingsViewModel
import com.shibi.sceptrum.ui.devices.DevicesActions
import com.shibi.sceptrum.ui.devices.DevicesScreen
import com.shibi.sceptrum.ui.nav.NavCarousel
import com.shibi.sceptrum.ui.nav.NavSection
import com.shibi.sceptrum.ui.nav.SectionRoutes
import com.shibi.sceptrum.ui.pairing.PairingActions
import com.shibi.sceptrum.ui.remote.AppsActions
import com.shibi.sceptrum.ui.remote.RemoteActions
import com.shibi.sceptrum.ui.remote.RemoteExtras
import com.shibi.sceptrum.ui.remote.RemotePanel
import com.shibi.sceptrum.ui.remote.RemoteScreen
import com.shibi.sceptrum.ui.remote.TypingActions
import com.shibi.sceptrum.ui.settings.SettingsScreen
import com.shibi.sceptrum.ui.theme.SceptrumTheme

/** Widest the carousel grows when unfolded, so the arc keeps its shape. */
private val MaxCarouselWidth = 480.dp

@Composable
fun SceptrumApp(appearance: AppearanceViewModel = viewModel()) {
    val darkTheme by appearance.darkTheme.collectAsStateWithLifecycle()
    val background by appearance.background.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val section = SectionRoutes.sectionFor(backStackEntry?.destination?.route) ?: NavSection.Remote
    val factory = LocalContext.current.appGraph.viewModelFactory
    val devices: DevicesViewModel = viewModel(factory = factory)
    val pairing: PairingViewModel = viewModel(factory = factory)
    val devicesState by devices.state.collectAsStateWithLifecycle()
    val pairingState by pairing.state.collectAsStateWithLifecycle()
    val deviceActions = DevicesActions(
        onSelect = devices::select,
        onEdit = devices::edit,
        onRenameChange = devices::onRenameChange,
        onSaveRename = devices::saveRename,
        onAskForget = devices::askForget,
        onConfirmForget = devices::confirmForget,
        onCloseEdit = devices::closeEdit,
        onPair = { pairing.begin(devices.targetFor(it)) },
        onRefresh = devices::refresh,
        onManualChange = devices::onManualChange,
        onManualSubmit = { devices.manualTarget()?.let(pairing::begin) },
    )
    val pairingActions = PairingActions(
        onCodeChange = pairing::onCodeChange,
        onRetry = pairing::retry,
        onCancel = pairing::cancel,
        onOpenRemote = {
            pairing.cancel()
            navController.openSection(NavSection.Remote)
        },
    )

    SceptrumFrame(darkTheme = darkTheme, background = background) { widthClass ->
        SceptrumScaffold(
            section = section,
            onSelect = { navController.openSection(it) },
        ) {
            NavHost(
                navController = navController,
                startDestination = SectionRoutes.Start,
            ) {
                composable(SectionRoutes.Remote) {
                    RemoteRoute(
                        widthClass = widthClass,
                        devicesState = devicesState,
                        deviceActions = deviceActions,
                        onOpenDevices = { navController.openSection(NavSection.Devices) },
                    )
                }
                composable(SectionRoutes.Devices) {
                    // Each visit to Devices scans for one window; leaving stops it.
                    DisposableEffect(Unit) {
                        devices.refresh()
                        onDispose { devices.stopScan() }
                    }
                    BackHandler(enabled = pairingState != PairingUiState.Idle) { pairing.cancel() }
                    BackHandler(enabled = pairingState == PairingUiState.Idle && devicesState.editing != null) {
                        devices.closeEdit()
                    }
                    DevicesScreen(
                        widthClass = widthClass,
                        state = devicesState,
                        pairing = pairingState,
                        actions = deviceActions,
                        pairingActions = pairingActions,
                    )
                }
                composable(SectionRoutes.Settings) {
                    val graph = LocalContext.current.appGraph
                    val settings: RemoteSettingsViewModel = viewModel(factory = graph.viewModelFactory)
                    val keyPressTyping by settings.keyPressTyping.collectAsStateWithLifecycle()
                    SettingsScreen(
                        widthClass = widthClass,
                        darkTheme = darkTheme,
                        background = background,
                        onDarkThemeChange = appearance::setDarkTheme,
                        onSelectBackground = appearance::selectBackground,
                        keyPressTyping = keyPressTyping,
                        onKeyPressTypingChange = settings::setKeyPressTyping,
                        versionName = remember(graph) { graph.versionName() },
                    )
                }
            }
        }
    }
}

/**
 * The remote, bound to the session: connected while this screen is started
 * (plus the session's grace period), with haptics for repeats and rejects.
 */
@Composable
private fun RemoteRoute(
    widthClass: WindowWidthClass,
    devicesState: DevicesUiState,
    deviceActions: DevicesActions,
    onOpenDevices: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.appGraph
    val remote: RemoteViewModel = viewModel(factory = graph.viewModelFactory)
    val keyboard: KeyboardViewModel = viewModel(factory = graph.viewModelFactory)
    val apps: AppsViewModel = viewModel(factory = graph.viewModelFactory)
    val session by remote.state.collectAsStateWithLifecycle()
    val power by remote.power.collectAsStateWithLifecycle()
    val keyboardState by keyboard.state.collectAsStateWithLifecycle()
    val appsState by apps.state.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf(RemotePanel.None) }
    var swipeMode by rememberSaveable { mutableStateOf(false) }
    val lanBlocked = remember(graph) { graph.lanAccess() == LanAccessState.Blocked }
    val haptics = LocalHapticFeedback.current
    LifecycleStartEffect(graph) {
        graph.sessionManager.acquire()
        onStopOrDispose { graph.sessionManager.release() }
    }
    LaunchedEffect(remote) {
        remote.haptics.collect {
            haptics.performHapticFeedback(
                when (it) {
                    RemoteHaptic.Tick -> HapticFeedbackType.SegmentFrequentTick
                    RemoteHaptic.Reject -> HapticFeedbackType.Reject
                },
            )
        }
    }
    LaunchedEffect(keyboard, apps) {
        merge(keyboard.rejects, apps.rejects).collect { haptics.performHapticFeedback(HapticFeedbackType.Reject) }
    }
    // The TV focused a text field: open the keyboard bar. Unfolded, the keyboard card is always shown.
    LaunchedEffect(keyboard) {
        keyboard.openRequests.collect { if (panel == RemotePanel.None) panel = RemotePanel.Keyboard }
    }
    BackHandler(enabled = panel != RemotePanel.None) { panel = RemotePanel.None }
    RemoteScreen(
        widthClass = widthClass,
        session = session,
        actions = RemoteActions(
            onTap = remote::tap,
            onPress = remote::press,
            onRelease = remote::release,
            onRetry = remote::retry,
            onOpenDevices = onOpenDevices,
        ),
        devices = devicesState,
        deviceActions = deviceActions,
        extras = RemoteExtras(
            keyboard = keyboardState,
            typing = TypingActions(
                onTextChange = keyboard::onTextChange,
                onBackspace = keyboard::backspace,
                onEnter = keyboard::enter,
                onClear = keyboard::clear,
            ),
            apps = appsState,
            appsActions = AppsActions(
                onLaunch = apps::launch,
                onEditing = apps::setEditing,
                onLabelChange = apps::onLabelChange,
                onLinkChange = apps::onLinkChange,
                onSubmit = apps::submit,
                onAddCurrent = apps::addCurrentApp,
                onRemove = apps::remove,
                onMoveUp = apps::moveUp,
                onMoveDown = apps::moveDown,
                onReset = apps::resetToDefaults,
            ),
            power = power,
            onDismissPower = remote::dismissPowerTip,
            panel = panel,
            onPanel = { panel = it },
            swipeMode = swipeMode,
            onSwipeMode = { swipeMode = it },
            autoFocusKeyboard = true,
            lanBlocked = lanBlocked,
            onOpenLanSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
        ),
    )
}

/**
 * Theme, backdrop and window size class. Dark theme paints the dark default
 * over the chosen backdrop without rewriting it.
 */
@Composable
fun SceptrumFrame(
    darkTheme: Boolean,
    background: AppBackground,
    content: @Composable (WindowWidthClass) -> Unit,
) {
    SceptrumTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(LocalReduceMotion provides systemReducesMotion()) {
            AppBackgroundHost(background = background.shownWith(darkTheme)) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    content(WindowWidthClass.fromWidthDp(maxWidth.value))
                }
            }
        }
    }
}

/** Section content above, the glass carousel in its own slot below. */
@Composable
fun SceptrumScaffold(
    section: NavSection,
    onSelect: (NavSection) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
        ) {
            content()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            NavCarousel(
                selected = section,
                onSelect = onSelect,
                modifier = Modifier.widthIn(max = MaxCarouselWidth),
            )
        }
    }
}

/** Section switch from the carousel: one copy of each section, state kept. */
private fun NavHostController.openSection(section: NavSection) {
    val route = SectionRoutes.routeFor(section)
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
