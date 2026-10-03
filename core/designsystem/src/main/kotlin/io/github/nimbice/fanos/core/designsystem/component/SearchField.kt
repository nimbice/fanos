package io.github.nimbice.fanos.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction

/**
 * A one-line search box. Searching puts the keyboard away; the X at the end clears the box and puts
 * the cursor back in it, ready for a new search. While it's empty it offers the [recent] searches,
 * and picking one searches for it ([onSearch], with the words put in the box).
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    recent: List<String> = emptyList(),
    onForget: (String) -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    WithRecentSearches(
        query = value,
        recent = recent,
        onPick = { words ->
            onValueChange(words)
            onSearch()
        },
        onForget = onForget,
        modifier = modifier,
    ) { anchor ->
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { FittingText(placeholder) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon =
                if (value.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = {
                            onValueChange("")
                            focusRequester.requestFocus()
                        }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                    }
                },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(onSearch = {
                    focus.clearFocus()
                    onSearch()
                }),
            modifier = anchor.fillMaxWidth().focusRequester(focusRequester),
        )
    }
}
