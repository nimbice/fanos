package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import io.github.nimbice.fanos.core.model.ReaderSettings
import kotlinx.coroutines.launch

/** Words to share as a picture: the passage, and where it's from. */
internal data class QuoteRequest(val quote: String, val novelTitle: String, val chapterTitle: String)

/** The picture's colours: the page's own, or plain light or dark. */
internal enum class QuoteLook { Page, Light, Dark }

/**
 * Shares a passage as a picture: the card as it will look (the passage in the reader's font, the novel and chapter
 * under it), in the page's colours or plain light or dark, and Share, which sends just that card to another app.
 */
@Composable
internal fun QuoteCardDialog(request: QuoteRequest, settings: ReaderSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val family = remember(settings.font, settings.ownFont) { readingFamily(context, settings) }
    var look by rememberSaveable { mutableStateOf(QuoteLook.Page) }
    var sending by remember { mutableStateOf(false) }
    val card = rememberGraphicsLayer()
    val colors = settings.colors
    // Wider than a dialog's usual width: the card reads as it will, and the three looks fit on a line.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).widthIn(max = DIALOG_MAX),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp)) {
                Text("Share as a picture", style = MaterialTheme.typography.titleLarge)
                // The card, kept as drawn for the picture; a long passage scrolls here, and goes whole into the picture.
                Box(Modifier.padding(top = 16.dp).heightIn(max = PREVIEW_MAX).verticalScroll(rememberScrollState())) {
                    Box(
                        Modifier.drawWithContent {
                            card.record { this@drawWithContent.drawContent() }
                            drawLayer(card)
                        },
                    ) {
                        QuoteCard(request, look, colors.background, colors.text, family)
                    }
                }
                FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuoteLook.entries.forEach { option ->
                        val (background, _) = option.colors(colors.background, colors.text)
                        FilterChip(
                            selected = look == option,
                            onClick = { look = option },
                            label = { Text(option.label) },
                            leadingIcon = {
                                Box(Modifier.size(14.dp).clip(CircleShape).background(background).border(1.dp, Color.Black.copy(alpha = 0.2f), CircleShape))
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        enabled = !sending,
                        onClick = {
                            sending = true
                            scope.launch {
                                try {
                                    sendPicture(context, card.toImageBitmap().asAndroidBitmap())
                                    onDismiss()
                                } finally {
                                    sending = false
                                }
                            }
                        },
                    ) { Text("Share") }
                }
            }
        }
    }
}

/** The card itself: the passage in quotes, in the reader's font, and the novel and chapter it's from. */
@Composable
private fun QuoteCard(request: QuoteRequest, look: QuoteLook, page: Color, text: Color, family: FontFamily) {
    val (background, ink) = look.colors(page, text)
    val quote = request.quote.trim().let { if (it.length > LONGEST) it.take(LONGEST).substringBeforeLast(' ') + "…" else it }
    // Smaller for a longer passage, so the card stays a card.
    val size = when {
        quote.length > 600 -> 14.sp
        quote.length > 300 -> 16.sp
        else -> 18.sp
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp),
    ) {
        Text(
            "“$quote”",
            color = ink,
            fontFamily = family,
            fontStyle = FontStyle.Italic,
            fontSize = size,
            lineHeight = size * 1.5f,
        )
        val from = listOf(request.novelTitle, request.chapterTitle).filter { it.isNotBlank() }
        if (from.isNotEmpty()) {
            Column(Modifier.padding(top = 14.dp)) {
                from.forEach { line -> Text(line, color = ink.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** The background and the text colour of a [QuoteLook], the page's being [page] and [text]. */
private fun QuoteLook.colors(page: Color, text: Color): Pair<Color, Color> =
    when (this) {
        QuoteLook.Page -> page to text
        QuoteLook.Light -> Color(0xFFFFFFFF) to Color(0xFF1F1B16)
        QuoteLook.Dark -> Color(0xFF1B1A19) to Color(0xFFECE6DE)
    }

private val QuoteLook.label: String
    get() =
        when (this) {
            QuoteLook.Page -> "Page colours"
            QuoteLook.Light -> "Light"
            QuoteLook.Dark -> "Dark"
        }

/** The most of a passage a card takes: past this, its end gives way to "…". */
private const val LONGEST = 900

private val PREVIEW_MAX = 420.dp
private val DIALOG_MAX = 560.dp
