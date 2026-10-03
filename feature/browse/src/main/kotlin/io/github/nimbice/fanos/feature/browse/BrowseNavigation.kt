package io.github.nimbice.fanos.feature.browse

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Browse. Set [SHOW_EXTENSIONS] true in its saved state to open it on the extensions. */
@Serializable
data object SourcesRoute {
    const val SHOW_EXTENSIONS = "show_extensions"
}

/** A source's lists and search; with [query], it opens on that search. */
@Serializable
data class CatalogRoute(val sourceId: String, val query: String? = null)

/** One search run in every source. */
@Serializable
data class GlobalSearchRoute(val query: String)

fun NavGraphBuilder.browseScreens(
    onOpenSource: (sourceId: String, query: String?) -> Unit,
    onSearchAll: (query: String) -> Unit,
    onOpenNovel: (novelId: Long) -> Unit,
    onOpenInBrowser: (url: String) -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit = {},
) {
    composable<SourcesRoute> { entry ->
        val showExtensions by entry.savedStateHandle.getStateFlow(SourcesRoute.SHOW_EXTENSIONS, false).collectAsStateWithLifecycle()
        BrowseScreen(
            showExtensions = showExtensions,
            onExtensionsShown = { entry.savedStateHandle[SourcesRoute.SHOW_EXTENSIONS] = false },
            onOpenSource = { onOpenSource(it, null) },
            onSearchAll = onSearchAll,
            onOpenNovel = onOpenNovel,
            onOpenLibrary = onOpenLibrary,
        )
    }
    composable<CatalogRoute> {
        CatalogScreen(onBack = onBack, onOpenNovel = onOpenNovel, onOpenInBrowser = onOpenInBrowser)
    }
    composable<GlobalSearchRoute> {
        GlobalSearchScreen(onBack = onBack, onOpenNovel = onOpenNovel, onOpenInBrowser = onOpenInBrowser, onOpenSource = onOpenSource)
    }
}
