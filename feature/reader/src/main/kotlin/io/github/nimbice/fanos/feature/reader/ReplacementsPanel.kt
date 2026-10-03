package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.model.Replacement

/**
 * Word replacements: the rules the novel's text is shown and read aloud with, a rule to a line, and a new one. A rule
 * can be the novel's own or for all novels, match whole words only, and match capitals.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReplacementsPanel(
    rules: List<Replacement>,
    onAdd: (find: String, replace: String, everywhere: Boolean, wholeWord: Boolean, matchCase: Boolean) -> Unit,
    onRemove: (Replacement) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var find by rememberSaveable { mutableStateOf("") }
    var replace by rememberSaveable { mutableStateOf("") }
    var everywhere by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(true) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = modifier, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Word replacements", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (rules.isEmpty()) {
                    item {
                        Text(
                            "Words replaced here change the text shown and read aloud: a name a translation gets wrong, a typo it repeats.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                items(rules, key = { it.id }) { rule ->
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text("${rule.find} → ${rule.replace}", style = MaterialTheme.typography.bodyLarge)
                            val how = listOfNotNull(if (rule.everywhere) "All novels" else "This novel", if (rule.wholeWord) "whole words" else null, if (rule.matchCase) "match capitals" else null)
                            Text(how.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onRemove(rule) }) { Icon(Icons.Filled.Close, contentDescription = "Remove ${rule.find}") }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = find, onValueChange = { find = it.replace("\n", "") }, label = { Text("Replace") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = replace, onValueChange = { replace = it.replace("\n", "") }, label = { Text("With") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !everywhere, onClick = { everywhere = false }, label = { Text("This novel") })
                    FilterChip(selected = everywhere, onClick = { everywhere = true }, label = { Text("All novels") })
                    FilterChip(selected = wholeWord, onClick = { wholeWord = !wholeWord }, label = { Text("Whole words") })
                    FilterChip(selected = matchCase, onClick = { matchCase = !matchCase }, label = { Text("Match capitals") })
                }
                TextButton(
                    enabled = find.isNotBlank(),
                    onClick = {
                        onAdd(find, replace, everywhere, wholeWord, matchCase)
                        find = ""
                        replace = ""
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Add") }
            }
        }
    }
}
