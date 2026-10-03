package io.github.nimbice.fanos.feature.reader

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data class ReaderRoute(val novelId: Long, val chapterId: Long)

fun NavGraphBuilder.readerScreen(
    onBack: () -> Unit,
    onOpenInBrowser: (url: String) -> Unit,
) {
    composable<ReaderRoute> { ReaderScreen(onBack = onBack, onOpenInBrowser = onOpenInBrowser) }
}
