package io.github.nimbice.fanos.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.designsystem.component.LoadingState
import io.github.nimbice.fanos.core.designsystem.component.NovelCover
import io.github.nimbice.fanos.core.designsystem.component.number
import io.github.nimbice.fanos.core.model.ReadingStats
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.BoxWithConstraints
import io.github.nimbice.fanos.core.designsystem.component.fittingStyle

/**
 * The Updates tab's Stats: today against the daily goal, the streak, this month, minutes a day this week (with the goal's
 * line), and this month by novel.
 */
@Composable
internal fun StatsPane(stats: ReadingStats?, goal: Int, onGoal: (Int) -> Unit) {
    when {
        stats == null -> LoadingState()
        else ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Goal(stats, goal, onGoal) }
                if (stats.isEmpty) {
                    item { Text("Your streak, time read and chapters show here as you read.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp)) }
                    return@LazyColumn
                }
                item { Summary(stats, goal) }
                if (stats.byNovel.isNotEmpty()) item { ByNovel(stats) }
                item {
                    Text(
                        "Chapters count from read marks; time read, listening too, from when this was added.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
    }
}

/** Today against the goal, and the goal's choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Goal(stats: ReadingStats, goal: Int, onGoal: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (goal > 0) {
                val today = stats.week.lastOrNull()?.seconds ?: 0L
                val goalSeconds = goal * 60L
                val met = stats.week.count { it.seconds >= goalSeconds }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Ring((today.toFloat() / goalSeconds).coerceIn(0f, 1f), "${today / 60} min")
                    Column {
                        Text("Today: ${today / 60} min \u00b7 goal ${goalLabel(goal)}", style = MaterialTheme.typography.titleMedium)
                        // Rounded up, so it and today's whole minutes add up to the goal.
                        val left = ((goalSeconds - today).coerceAtLeast(0) + 59) / 60
                        Text(
                            (if (left > 0) "$left minutes to go" else "Goal met today") + " \u00b7 met $met of the last 7 days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text("Daily goal", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = if (goal > 0) 14.dp else 0.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 60, 120).forEach { minutes ->
                    FilterChip(selected = goal == minutes, onClick = { onGoal(minutes) }, label = { Text(if (minutes == 0) "Off" else goalLabel(minutes)) })
                }
            }
        }
    }
}

/** Progress round a circle, with [label] in the middle. */
@Composable
private fun Ring(fraction: Float, label: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val done = MaterialTheme.colorScheme.primary
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
            if (fraction > 0f) drawArc(done, -90f, 360f * fraction, false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
        }
        // Inside the ring, a size smaller when it wouldn't fit there (a large text size).
        val fitted = fittingStyle(listOf(label), RING_INSIDE, MaterialTheme.typography.labelLarge)
        Text(label, style = fitted, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** The room inside the goal's ring, across: the ring less its stroke both sides, and a little. */
private val RING_INSIDE = 60.dp

private fun goalLabel(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"

@Composable
private fun Summary(stats: ReadingStats, goal: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // Three a row, a third each: their figures a size smaller when one wouldn't fit on its line (a large text size).
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val values = listOf(days(stats.streak), number(stats.chaptersThisMonth), duration(stats.secondsThisMonth))
                val figure = fittingStyle(values, (maxWidth - FIGURE_GAP * 2) / 3, MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(FIGURE_GAP)) {
                    Figure(values[0], "reading streak", figure, Modifier.weight(1f), if (stats.longestStreak > 0) "Longest: ${days(stats.longestStreak)}" else null)
                    Figure(values[1], "chapters this month", figure, Modifier.weight(1f))
                    Figure(values[2], "read this month", figure, Modifier.weight(1f))
                }
            }
            val goalSeconds = goal * 60L
            val most = maxOf(stats.week.maxOfOrNull { it.seconds } ?: 0L, goalSeconds).takeIf { it > 0 } ?: 1L
            val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .padding(top = 16.dp)
                    // The goal, across the bars: those that reach it are drawn full, the rest paler.
                    .drawWithContent {
                        drawContent()
                        if (goalSeconds > 0) {
                            val y = size.height * (1f - goalSeconds.toFloat() / most)
                            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
                        }
                    },
            ) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    stats.week.forEach { day ->
                        val share = day.seconds.toFloat() / most
                        val reached = goalSeconds == 0L || day.seconds >= goalSeconds
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(if (day.seconds > 0) share.coerceAtLeast(0.04f) else 0.02f)
                                .background(
                                    when {
                                        day.seconds == 0L -> MaterialTheme.colorScheme.outlineVariant
                                        reached -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    },
                                    RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                                ),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                stats.week.forEach { day ->
                    Text(
                        day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                if (goal > 0) "Minutes a day, this week \u00b7 the line is the goal" else "Minutes a day, this week",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Figure(value: String, label: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, more: String? = null) {
    Column(modifier) {
        Text(value, style = style, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        more?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
    }
}

private fun days(count: Int): String = if (count == 1) "1 day" else "${number(count)} days"

@Composable
private fun ByNovel(stats: ReadingStats) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("This month by novel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            stats.byNovel.forEach { novel ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NovelCover(novel.coverUrl, novel.title, Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(novel.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val parts = listOfNotNull(if (novel.chapters > 0) "${number(novel.chapters)} chapters" else null, if (novel.seconds >= 60) duration(novel.seconds) else null)
                        Text(parts.joinToString(" \u00b7 "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** "45 min", "2.5 h", "31 h". */
private fun duration(seconds: Long): String =
    when {
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 36_000 -> "${(seconds / 360.0).roundToInt() / 10.0} h".replace(".0 h", " h")
        else -> "${number((seconds / 3600).toInt())} h"
    }

/** Room between the summary's three figures. */
private val FIGURE_GAP = 16.dp
