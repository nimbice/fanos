package io.github.nimbice.fanos.feature.novel

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/** A novel's page; [movedTo] names its site when it has just been moved there, which the page says once. */
@Serializable
data class NovelRoute(val novelId: Long, val movedTo: String? = null)

fun NavGraphBuilder.novelScreen(
    onBack: () -> Unit,
    onOpenChapter: (novelId: Long, chapterId: Long) -> Unit,
    onOpenInBrowser: (url: String) -> Unit,
    onOpenExtensions: () -> Unit,
    onMove: (novelId: Long) -> Unit,
) {
    composable<NovelRoute> { entry ->
        NovelScreen(
            onBack = onBack,
            onOpenChapter = onOpenChapter,
            onOpenInBrowser = onOpenInBrowser,
            onOpenExtensions = onOpenExtensions,
            onMove = onMove,
            movedTo = entry.toRoute<NovelRoute>().movedTo,
        )
    }
}
