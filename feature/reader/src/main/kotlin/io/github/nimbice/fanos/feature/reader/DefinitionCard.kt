package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.model.Definition
import java.net.ConnectException
import java.net.UnknownHostException
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext

/** A word being looked up in the dictionary, and how that went. */
internal sealed interface Lookup {
    val word: String

    data class Looking(override val word: String) : Lookup

    data class Found(override val word: String, val definition: Definition) : Lookup

    data class NotFound(override val word: String) : Lookup

    data class Failed(override val word: String, val message: String) : Lookup
}

/** What to say when the built-in dictionary hasn't the word and the online ones couldn't be asked: offline, or not answering. */
internal fun lookupFailure(error: Throwable): String =
    when (error) {
        is UnknownHostException, is ConnectException -> "The built-in dictionary hasn't got this word, and there's no connection to look online."
        else -> "The built-in dictionary hasn't got this word, and the online ones aren't answering just now."
    }

/** A definition as text: the word, then its senses by part of speech, then where it's from, for copying. */
internal fun Definition.asText(): String =
    buildString {
        append(word)
        phonetic?.let { append(" ").append(it) }
        for (meaning in meanings) {
            append("\n\n").append(meaning.partOfSpeech)
            meaning.senses.forEachIndexed { i, sense ->
                append("\n").append(i + 1).append(". ").append(sense.definition)
                sense.example?.let { append("\n   “").append(it).append("”") }
            }
        }
        append("\n\nFrom ").append(source)
        licence?.let { append(" (").append(it).append(")") }
        sourceUrl?.let { append("\n").append(it) }
    }

/**
 * The dictionary's card over the page: the word (the base form it was found as), how it's said, and its meanings by
 * part of speech; other apps to look it up in, copying it, and closing.
 */
@Composable
internal fun DefinitionCard(
    lookup: Lookup,
    onOtherApps: () -> Unit,
    onCopy: (() -> Unit)?,
    onSay: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 6.dp)) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (lookup) {
                    is Lookup.Looking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(lookup.word, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        Text("Looking it up…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Lookup.Found -> {
                        val definition = lookup.definition
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(definition.word, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                            Text(
                                definition.phonetic.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(start = 8.dp, top = 3.dp),
                            )
                            SayButton { onSay(definition.word) }
                        }
                        for (meaning in definition.meanings) {
                            Text(
                                meaning.partOfSpeech,
                                style = MaterialTheme.typography.labelLarge,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            meaning.senses.forEachIndexed { i, sense ->
                                Text("${i + 1}. ${sense.definition}", style = MaterialTheme.typography.bodyMedium)
                                sense.example?.let {
                                    Text(
                                        "“$it”",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                    is Lookup.NotFound -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(lookup.word, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
                            SayButton { onSay(lookup.word) }
                        }
                        Text("No definition found. Another app may have one.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Lookup.Failed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(lookup.word, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
                            SayButton { onSay(lookup.word) }
                        }
                        Text(lookup.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOtherApps) { Text("Other apps") }
                if (onCopy != null) TextButton(onClick = onCopy) { Text("Copy") }
                // Where the definition is from, under what licence (Wiktionary's words are shared on condition they're
                // credited), and a tap opens the entry itself.
                val found = (lookup as? Lookup.Found)?.definition
                val context = LocalContext.current
                Text(
                    listOfNotNull(found?.source, found?.licence).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .weight(1f)
                            .then(found?.sourceUrl?.let { url -> Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } ?: Modifier)
                            .padding(horizontal = 4.dp),
                )
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

/** The word said aloud, in the listening voice. */
@Composable
private fun SayButton(onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick) { Icon(ReaderIcons.Speaker, contentDescription = "Say it aloud") }
}
