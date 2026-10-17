package com.shibi.sceptrum.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.shibi.sceptrum.appearance.AppearanceViewModel
import com.shibi.sceptrum.domain.AppBackground
import com.shibi.sceptrum.domain.WindowWidthClass
import com.shibi.sceptrum.domain.shownWith
import com.shibi.sceptrum.ui.background.AppBackgroundHost
import com.shibi.sceptrum.ui.devices.DevicesScreen
import com.shibi.sceptrum.ui.nav.NavCarousel
import com.shibi.sceptrum.ui.nav.NavSection
import com.shibi.sceptrum.ui.nav.SectionRoutes
import com.shibi.sceptrum.ui.remote.RemoteScreen
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

    SceptrumFrame(darkTheme = darkTheme, background = background) { widthClass ->
        SceptrumScaffold(
            section = section,
            onSelect = { navController.openSection(it) },
        ) {
            NavHost(
                navController = navController,
                startDestination = SectionRoutes.Start,
            ) {
                composable(SectionRoutes.Remote) { RemoteScreen(widthClass) }
                composable(SectionRoutes.Devices) { DevicesScreen(widthClass) }
                composable(SectionRoutes.Settings) {
                    SettingsScreen(
                        widthClass = widthClass,
                        darkTheme = darkTheme,
                        background = background,
                        onDarkThemeChange = appearance::setDarkTheme,
                        onSelectBackground = appearance::selectBackground,
                    )
                }
            }
        }
    }
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
        AppBackgroundHost(background = background.shownWith(darkTheme)) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                content(WindowWidthClass.fromWidthDp(maxWidth.value))
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
