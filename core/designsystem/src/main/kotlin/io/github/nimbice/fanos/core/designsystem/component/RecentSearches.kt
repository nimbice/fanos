package io.github.nimbice.fanos.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons

/**
 * A search box with its [recent] searches under it, newest first, while it's empty and has the keyboard's focus. They
 * lie over what's below until one is picked, the box is typed in or left, or they're tapped away. Picking one puts the
 * keyboard away and searches for it ([onPick]); its ✕ forgets it ([onForget]). [field] is the box, given the modifier
 * that anchors the list to it and follows its focus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithRecentSearches(
    query: String,
    recent: List<String>,
    onPick: (String) -> Unit,
    onForget: (String) -> Unit,
    modifier: Modifier = Modifier,
    field: @Composable (Modifier) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // Tapped away, Back, or a tap on the box itself (which also brings them back): they stay away until the box is
    // left or typed in.
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(focused, query.isEmpty()) { dismissed = false }
    val showing = focused && query.isEmpty() && recent.isNotEmpty() && !dismissed
    ExposedDropdownMenuBox(expanded = showing, onExpandedChange = { dismissed = !it }, modifier = modifier) {
        field(Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).onFocusChanged { focused = it.hasFocus })
        ExposedDropdownMenu(expanded = showing, onDismissRequest = { dismissed = true }) {
            recent.forEach { words ->
                DropdownMenuItem(
                    text = { Text(words, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        focusManager.clearFocus()
                        onPick(words)
                    },
                    leadingIcon = { Icon(ReaderIcons.History, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { onForget(words) }) { Icon(Icons.Filled.Close, contentDescription = "Forget \u201c$words\u201d") }
                    },
                )
            }
        }
    }
}
