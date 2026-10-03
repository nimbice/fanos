package io.github.nimbice.fanos.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.nimbice.fanos.BuildConfig
import io.github.nimbice.fanos.core.designsystem.component.LocalTabBar
import io.github.nimbice.fanos.core.designsystem.component.TabBarState
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.fittingStyle
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.feature.browse.CatalogRoute
import io.github.nimbice.fanos.feature.browse.GlobalSearchRoute
import io.github.nimbice.fanos.feature.browse.MoveNovelRoute
import io.github.nimbice.fanos.feature.browse.MoveNovelsRoute
import io.github.nimbice.fanos.feature.browse.SourcesRoute
import io.github.nimbice.fanos.feature.browse.browseScreens
import io.github.nimbice.fanos.feature.browse.moveNovelScreen
import io.github.nimbice.fanos.feature.browse.moveNovelsScreen
import io.github.nimbice.fanos.feature.library.DownloadsRoute
import io.github.nimbice.fanos.feature.library.LibraryRoute
import io.github.nimbice.fanos.feature.library.SectionsRoute
import io.github.nimbice.fanos.feature.library.RecentRoute
import io.github.nimbice.fanos.feature.library.downloadsScreen
import io.github.nimbice.fanos.feature.library.libraryScreen
import io.github.nimbice.fanos.feature.library.recentScreen
import io.github.nimbice.fanos.feature.novel.NovelRoute
import io.github.nimbice.fanos.feature.novel.novelScreen
import io.github.nimbice.fanos.feature.reader.ReaderRoute
import io.github.nimbice.fanos.feature.reader.readerScreen
import io.github.nimbice.fanos.feature.settings.SettingsRoute
import io.github.nimbice.fanos.feature.settings.SettingsTopic
import io.github.nimbice.fanos.feature.settings.SettingsTopicRoute
import io.github.nimbice.fanos.feature.settings.settingsScreens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlin.reflect.KClass

/** How long a screen takes to fade in or out. */
private const val FADE_MILLIS = 250

/** The most the Updates tab's number shows; more reads as that with a plus. */
private const val MAX_SHOWN_COUNT = 99

/** The room Material's bar leaves between its tabs, and what a label keeps clear of its tab's edges. */
private val TabGap = 8.dp
private val TabLabelMargin = 4.dp

private data class TopLevel(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector)

private val topLevel =
    listOf(
        TopLevel(LibraryRoute, LibraryRoute::class, "Library", ReaderIcons.Library),
        TopLevel(RecentRoute, RecentRoute::class, "Updates", ReaderIcons.NewReleases),
        TopLevel(SourcesRoute, SourcesRoute::class, "Browse", ReaderIcons.Explore),
        TopLevel(DownloadsRoute, DownloadsRoute::class, "Downloads", ReaderIcons.Download),
        TopLevel(SettingsRoute, SettingsRoute::class, "Settings", Icons.Filled.Settings),
    )

/** A place in the app that something outside it, such as a tapped notification, asks to show. */
sealed interface AppLink {
    /** The new chapters, in the Updates tab, as the new-chapters summary asks. */
    data object NewChapters : AppLink

    data class Novel(val novelId: Long) : AppLink

    /** A chapter to read, as the continue-reading widget asks. */
    data class Chapter(val novelId: Long, val chapterId: Long) : AppLink

    /** The Downloads tab, as a download notification asks. */
    data object Downloads : AppLink

    /** The backup settings, as a failed backup's notification asks. */
    data object Backup : AppLink

    /** The app's updates, in the settings. */
    data object AppUpdates : AppLink

    /** The extensions, which have updates. */
    data object Extensions : AppLink
}

/** To a tab: each keeps where it was left, and Back from any of them goes to the library. */
private fun NavHostController.navigateToTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The app's screens. [links] brings places to show from outside the app. */
@Composable
fun FanosApp(links: Flow<AppLink> = emptyFlow(), frame: FrameViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val unseenUpdates by frame.unseenUpdates.collectAsStateWithLifecycle()
    // A tab's screen can ask the tab bar to make way, as the Updates tab does for its selection's actions.
    val tabBar = remember { TabBarState() }
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showBottomBar = topLevel.any { item -> destination?.hasRoute(item.routeClass) == true }
    val back: () -> Unit = { navController.popBackStack() }
    val openInBrowser: (String) -> Unit = { url -> navController.navigate(BrowserRoute(url)) }
    val openSettingsTopic: (SettingsTopic) -> Unit = { topic -> navController.navigate(SettingsTopicRoute(topic.name)) }
    // As if picked from the tab bar and then from Settings: Back goes to Settings.
    val showSettingsTopic: (SettingsTopic) -> Unit = { topic ->
        navController.navigateToTopLevel(SettingsRoute)
        navController.popBackStack(SettingsRoute, inclusive = false)
        openSettingsTopic(topic)
    }

    LaunchedEffect(navController) {
        // Not before the graph is in place: a tap can be what started the app.
        navController.currentBackStackEntryFlow.first()
        links.collect { link ->
            val top = navController.currentBackStackEntry
            when (link) {
                AppLink.NewChapters -> {
                    navController.navigateToTopLevel(RecentRoute)
                    navController.currentBackStackEntry?.savedStateHandle?.set(RecentRoute.SHOW_NEW_CHAPTERS, true)
                }
                is AppLink.Novel -> {
                    val showing = top?.destination?.hasRoute(NovelRoute::class) == true && top.toRoute<NovelRoute>().novelId == link.novelId
                    // As if picked from the library: Back goes there, not to wherever the app was left.
                    if (!showing) navController.navigate(NovelRoute(link.novelId)) { popUpTo(LibraryRoute) }
                }
                // As if picked from the library and then the novel's page: Back goes to the novel, then the library.
                is AppLink.Chapter -> {
                    navController.navigate(NovelRoute(link.novelId)) { popUpTo(LibraryRoute) }
                    navController.navigate(ReaderRoute(link.novelId, link.chapterId))
                }
                AppLink.Downloads -> navController.navigateToTopLevel(DownloadsRoute)
                AppLink.Backup -> showSettingsTopic(SettingsTopic.Backup)
                AppLink.AppUpdates -> showSettingsTopic(SettingsTopic.Updates)
                AppLink.Extensions -> {
                    navController.navigateToTopLevel(SourcesRoute)
                    navController.currentBackStackEntry?.savedStateHandle?.set(SourcesRoute.SHOW_EXTENSIONS, true)
                }
            }
        }
    }

    CompositionLocalProvider(LocalTabBar provides tabBar) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (showBottomBar && !tabBar.hidden) BoxWithConstraints {
                    val labelStyle = fittingStyle(topLevel.map { it.label }, (maxWidth - TabGap * (topLevel.size - 1)) / topLevel.size - TabLabelMargin * 2, MaterialTheme.typography.labelMedium)
                    NavigationBar {
                        topLevel.forEach { item ->
                            NavigationBarItem(
                                selected = destination?.hierarchy?.any { it.hasRoute(item.routeClass) } == true,
                                onClick = { navController.navigateToTopLevel(item.route) },
                                icon = {
                                    // The Updates tab counts what came in since it was last looked at.
                                    val count = if (item.route == RecentRoute) unseenUpdates else 0
                                    BadgedBox(
                                        badge = {
                                            if (count > 0) {
                                                Badge(Modifier.semantics { contentDescription = countOf(count, "new chapter") }) {
                                                    Text(if (count > MAX_SHOWN_COUNT) "$MAX_SHOWN_COUNT+" else "$count")
                                                }
                                            }
                                        },
                                    ) { Icon(item.icon, contentDescription = null) }
                                },
                                label = { Text(item.label, style = labelStyle, maxLines = 1, softWrap = false) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            // Screens fade: the new one in over the old, and going back, the one left fades away over the one
            // beneath, which stays whole. The back gesture fades too, with the finger: the library's own default
            // for it shrinks the screen into a card that then snaps away, which read as awkward.
            NavHost(
                navController = navController,
                startDestination = LibraryRoute,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                enterTransition = { fadeIn(tween(FADE_MILLIS)) },
                exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { fadeOut(tween(FADE_MILLIS)) },
                predictivePopEnterTransition = { EnterTransition.None },
                predictivePopExitTransition = { fadeOut(tween(FADE_MILLIS)) },
            ) {
                libraryScreen(
                    onOpenNovel = { navController.navigate(NovelRoute(it)) },
                    onOpenSections = { navController.navigate(SectionsRoute) },
                    onMoveNovels = { navController.navigate(MoveNovelsRoute) },
                    onBack = back,
                )
                recentScreen(
                    onOpenNovel = { navController.navigate(NovelRoute(it)) },
                    onOpenChapter = { novelId, chapterId -> navController.navigate(ReaderRoute(novelId, chapterId)) },
                )
                browseScreens(
                    onOpenSource = { sourceId, query -> navController.navigate(CatalogRoute(sourceId, query)) },
                    onSearchAll = { navController.navigate(GlobalSearchRoute(it)) },
                    onOpenNovel = { navController.navigate(NovelRoute(it)) },
                    onOpenInBrowser = openInBrowser,
                    onBack = back,
                    onOpenLibrary = { navController.navigateToTopLevel(LibraryRoute) },
                )
                novelScreen(
                    onBack = back,
                    onOpenChapter = { novelId, chapterId -> navController.navigate(ReaderRoute(novelId, chapterId)) },
                    onOpenInBrowser = openInBrowser,
                    // As if picked from the tab bar, opened on the extensions.
                    onOpenExtensions = {
                        navController.navigateToTopLevel(SourcesRoute)
                        navController.currentBackStackEntry?.savedStateHandle?.set(SourcesRoute.SHOW_EXTENSIONS, true)
                    },
                    onMove = { navController.navigate(MoveNovelRoute(it)) },
                )
                // The moved novel's page takes the place of the one it was moved from; a novel Move novels didn't find, looked for
                // on its one site, goes back there.
                moveNovelScreen(
                    onBack = back,
                    onMoved = { moved ->
                        if (moved.onlySite) {
                            navController.popBackStack()
                        } else {
                            navController.navigate(NovelRoute(moved.novelId, movedTo = moved.site)) { popUpTo<NovelRoute> { inclusive = true } }
                        }
                    },
                    onOpenInBrowser = openInBrowser,
                )
                moveNovelsScreen(
                    onBack = back,
                    onSearch = { novelId, sourceId -> navController.navigate(MoveNovelRoute(novelId, sourceId)) },
                    onOpenInBrowser = openInBrowser,
                )
                readerScreen(onBack = back, onOpenInBrowser = openInBrowser)
                downloadsScreen(onOpenNovel = { navController.navigate(NovelRoute(it)) })
                settingsScreens(
                    versionName = BuildConfig.VERSION_NAME,
                    onOpenTopic = openSettingsTopic,
                    onOpenInBrowser = openInBrowser,
                    onOpenDownloads = { navController.navigateToTopLevel(DownloadsRoute) },
                    // As if picked from the tab bar, opened on the extensions.
                    onOpenExtensions = {
                        navController.navigateToTopLevel(SourcesRoute)
                        navController.currentBackStackEntry?.savedStateHandle?.set(SourcesRoute.SHOW_EXTENSIONS, true)
                    },
                    onBack = back,
                )
                composable<BrowserRoute> { backStackEntry ->
                    BrowserScreen(url = backStackEntry.toRoute<BrowserRoute>().url, onClose = back)
                }
            }
        }
    }
}
