package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.BreakIterator
import java.util.Locale

/** Where the reader left off, coming back after a while: when they last read, the lines just before, and their chapter. */
internal data class Recap(val readAt: Long, val quote: String, val chapterTitle: String)

/**
 * The last lines read before character [offset] of paragraph [block] of [paragraphs]: the last few sentences, about a
 * paragraph's worth, after an ellipsis; null when there's nothing before (a chapter's start).
 */
internal fun recapQuote(paragraphs: List<String>, block: Int, offset: Int, locale: Locale = Locale.getDefault()): String? {
    if (block !in paragraphs.indices) return null
    // Back from where reading stopped, a paragraph at a time, until there's enough to choose from.
    val parts = ArrayList<String>()
    var length = 0
    var at = block
    while (at >= 0 && length < ENOUGH) {
        val text = if (at == block) paragraphs[at].take(offset.coerceIn(0, paragraphs[at].length)) else paragraphs[at]
        if (text.isNotBlank()) {
            parts.add(0, text.trim())
            length += text.length
        }
        at--
    }
    val before = parts.joinToString(" ").trim()
    if (before.isEmpty()) return null
    val breaker = BreakIterator.getSentenceInstance(locale)
    breaker.setText(before)
    val sentences = ArrayList<String>()
    var end = breaker.last()
    var start = breaker.previous()
    while (start != BreakIterator.DONE && sentences.size < SENTENCES) {
        before.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { sentences.add(0, it) }
        end = start
        start = breaker.previous()
    }
    while (sentences.size > 1 && sentences.sumOf { it.length + 1 } > LONGEST) sentences.removeAt(0)
    var quote = sentences.joinToString(" ")
    // One long sentence: its end, from a word's start.
    if (quote.length > LONGEST) quote = quote.takeLast(LONGEST).let { cut -> cut.substring((cut.indexOf(' ') + 1).coerceAtMost(cut.length)) }
    return "…" + quote
}

/** How long ago [millis] back was, roughly, in words: "an hour ago", "5 days ago", "3 weeks ago". */
internal fun awhileAgo(millis: Long): String {
    val hours = millis / HOUR_MS
    val days = hours / 24
    return when {
        hours < 1 -> "a little while ago"
        hours < 24 -> if (hours == 1L) "an hour ago" else "$hours hours ago"
        days < 2 -> "yesterday"
        days < 14 -> "$days days ago"
        days < 60 -> "${days / 7} weeks ago"
        days < 365 -> "${days / 30} months ago"
        else -> "over a year ago"
    }
}

/** The card over the page on coming back after a while: where the reader left off, the lines just before it. */
@Composable
internal fun RecapCard(recap: Recap, now: Long, onCarryOn: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(end = 10.dp)) {
                Text("Where you left off", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    awhileAgo(now - recap.readAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                recap.quote,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, end = 10.dp),
            )
            Text(
                recap.chapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, end = 10.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = onCarryOn) { Text("Carry on") }
            }
        }
    }
}

private const val HOUR_MS = 60L * 60 * 1000

/** How much text before the place to choose the last sentences from. */
private const val ENOUGH = 600

/** At most so many sentences, and so many characters, read back. */
private const val SENTENCES = 3
private const val LONGEST = 280
