package io.github.nimbice.fanos.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.data.release.ReleasePattern
import io.github.nimbice.fanos.core.data.release.dueOn
import io.github.nimbice.fanos.core.data.release.patternText
import io.github.nimbice.fanos.core.designsystem.component.EmptyState
import io.github.nimbice.fanos.core.designsystem.component.LoadingState
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** A library novel in the week's calendar: when its chapters usually come out, and whether today's is out yet. */
data class CalendarNovel(val novelId: Long, val title: String, val coverUrl: String?, val pattern: ReleasePattern, val outToday: Boolean)

/**
 * This week: today's novels, then the ones with chapters out most days, then the days ahead, each with the library
 * novels that usually have a chapter out on it. Today's (and most days') say whether it's out yet; a tap opens the novel.
 */
@Composable
internal fun ReleaseCalendarPane(novels: List<CalendarNovel>?, onOpenNovel: (Long) -> Unit) {
    when {
        novels == null -> LoadingState()
        novels.isEmpty() ->
            EmptyState("No patterns yet", "When library novels' new chapters usually come out shows here, once there are a few weeks to go by.")
        else -> {
            val today = LocalDate.now()
            val mostDays = novels.filter { it.pattern == ReleasePattern.MostDays }
            val week =
                (0L until DAYS_SHOWN).map { today.plusDays(it) }
                    .map { day -> day to novels.filter { it.pattern != ReleasePattern.MostDays && it.pattern.dueOn(day) } }
                    .filter { (_, due) -> due.isNotEmpty() }
            // Today's own first: what's due now shouldn't wait below the many that come out most days.
            val (todays, ahead) = week.partition { (day, _) -> day == today }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                todays.forEach { (day, due) ->
                    item(key = "day $day") { CalendarHeading(calendarDay(day, today)) }
                    items(due, key = { "$day ${it.novelId}" }) { CalendarRow(it, today = true, onOpenNovel) }
                }
                if (mostDays.isNotEmpty()) {
                    item(key = "most") { CalendarHeading("Most days") }
                    items(mostDays, key = { "most ${it.novelId}" }) { CalendarRow(it, today = true, onOpenNovel) }
                }
                ahead.forEach { (day, due) ->
                    item(key = "day $day") { CalendarHeading(calendarDay(day, today)) }
                    items(due, key = { "$day ${it.novelId}" }) { CalendarRow(it, today = day == today, onOpenNovel) }
                }
                item(key = "foot") {
                    Text(
                        "Novels with no clear pattern yet aren't shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A novel under its day: its cover, title and pattern; on today's, whether the chapter's out yet. */
@Composable
private fun CalendarRow(novel: CalendarNovel, today: Boolean, onOpenNovel: (Long) -> Unit) {
    RecentRow(
        coverUrl = novel.coverUrl,
        title = novel.title,
        // Under "Most days", saying so again would add nothing.
        line = if (novel.pattern == ReleasePattern.MostDays) "" else patternText(novel.pattern),
        faded = false,
        onClick = { onOpenNovel(novel.novelId) },
    ) {
        if (today) {
            val out = novel.outToday
            Text(
                if (out) "Out" else "Not yet",
                style = MaterialTheme.typography.labelMedium,
                color = if (out) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (out) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** "Today · Thursday", "Tomorrow · Friday", then the day's name. */
private fun calendarDay(day: LocalDate, today: LocalDate): String {
    val name = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    return when (day) {
        today -> "Today · $name"
        today.plusDays(1) -> "Tomorrow · $name"
        else -> name
    }
}

/** Today and the six days after it. */
private const val DAYS_SHOWN = 7L
