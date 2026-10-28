package io.github.nimbice.fanos.feature.novel

import io.github.nimbice.fanos.core.designsystem.component.number
import io.github.nimbice.fanos.core.model.Chapter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

private const val DAY_MS = 86_400_000L

/**
 * The day the chapter list gives [chapter]: the site's date, or, for an undated chapter that turned up after the novel's
 * first fetch, the day this device first saw it listed. Null for an undated chapter the novel came with: when that came
 * out, nothing here can say.
 */
internal fun chapterDay(chapter: Chapter, zone: ZoneId): LocalDate? =
    chapter.publishedAt?.let { siteDay(it, zone) }
        ?: chapter.addedAt?.takeIf { chapter.seenAfter != null }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

/**
 * The day a site's date falls on. A date with no time comes as midnight UTC, and is that day wherever the reader is
 * (west of Greenwich, it would otherwise be the evening before); a time of day is the reader's day.
 */
internal fun siteDay(millis: Long, zone: ZoneId): LocalDate =
    if (millis % DAY_MS == 0L) LocalDate.ofEpochDay(millis / DAY_MS) else Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** "Today" for any time since midnight, else "1 day ago", "16 days ago": calendar days, not 24 hours. */
internal fun daysAgo(day: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0 -> "Today"
        days == 1L -> "1 day ago"
        else -> "${number(days.toInt())} days ago"
    }
}

/** What a chapter row says of its day: "Oct 12, 2026 · 16 days ago", or null with no day to give. */
internal fun chapterAge(chapter: Chapter, today: LocalDate, zone: ZoneId): String? =
    chapterDay(chapter, zone)?.let { "${it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))} · ${daysAgo(it, today)}" }
