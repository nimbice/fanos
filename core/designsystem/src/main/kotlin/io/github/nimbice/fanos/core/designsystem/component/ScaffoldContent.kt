package io.github.nimbice.fanos.core.designsystem.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier

/**
 * A screen's content under its Scaffold: padded by the Scaffold's [padding], and ending above the keyboard while it's up.
 * The app draws edge to edge, so the window doesn't shrink for the keyboard: without this, a list runs on under it with
 * its last rows out of reach, and a field low on the page stays hidden while it's typed in.
 */
fun Modifier.scaffoldContent(padding: PaddingValues): Modifier = padding(padding).consumeWindowInsets(padding).imePadding()
