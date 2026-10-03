package io.github.nimbice.fanos.core.data.release

import io.github.nimbice.fanos.core.model.Chapter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** When a novel's chapters come out, going by when the last few weeks' did. */
sealed interface ReleasePattern {
    /** On these days of the week, the [next] of them due then. */
    data class Weekdays(val days: List<DayOfWeek>, val next: LocalDate) : ReleasePattern

    /** Most days of the week. */
    data object MostDays : ReleasePattern

    /** Every so many [days], the [next] due then. */
    data class Every(val days: Int, val next: LocalDate) : ReleasePattern
}

/**
 * The days [chapters] came out: as the site dates them, or, most of them undated, the days this device first saw them
 * (leaving out the ones that came with the novel when it was added).
 */
fun releaseDays(chapters: List<Chapter>, zone: ZoneId): List<LocalDate> {
    val dated = chapters.mapNotNull { it.publishedAt }
    val seen = chapters.mapNotNull { it.addedAt }
    return releaseTimes(chapters.size, dated.size, seen.minOrNull(), dated, seen).map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
}

/**
 * When a novel's chapters came out, of the [published] dates and the [added] times (when this device first saw them)
 * at hand: the site's dates when it dates most of the novel's [chapters] ([dated] of them), else the added times, less
 * the ones that came with the novel when it was added, first of all at [firstAdded].
 */
fun releaseTimes(chapters: Int, dated: Int, firstAdded: Long?, published: List<Long>, added: List<Long>): List<Long> {
    if (dated > 0 && dated >= chapters * DATED_SHARE) return published
    val first = firstAdded ?: return emptyList()
    return added.filter { it > first + FIRST_BATCH_MS }
}

/**
 * The pattern in the [days] chapters came out, as of [today]: the days of the week most weeks have one on, most days,
 * or every so many days. Null without a pattern clear enough to go by, or when the last chapter is long past due.
 */
fun releasePattern(days: Collection<LocalDate>, today: LocalDate): ReleasePattern? {
    val all = days.toSortedSet()
    val last = all.lastOrNull() ?: return null
    val recent = all.filter { it > last.minusDays(WINDOW_DAYS) }
    if (recent.size < MIN_RELEASES) return null
    val first = recent.first()
    val span = ChronoUnit.DAYS.between(first, last) + 1
    if (span < MIN_SPAN) return null
    val gaps = recent.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
    val typical = gaps.sorted()[gaps.size / 2]
    // Long past when the next was due: a pause, not a pattern to go by.
    if (ChronoUnit.DAYS.between(last, today) > maxOf(STALE_DAYS, typical * 3L)) return null
    if (recent.size >= span * MOST_DAYS) return ReleasePattern.MostDays
    // The days of the week most weeks had a chapter on, when they had most of the chapters.
    val counts = recent.groupingBy { it.dayOfWeek }.eachCount()
    val regular = DayOfWeek.entries.filter { day -> (counts[day] ?: 0).let { it >= 2 && it >= occurrences(day, first, last) * REGULAR_SHARE } }
    if (regular.size in 1..MAX_WEEKDAYS && regular.sumOf { counts[it] ?: 0 } >= recent.size * COVERED_SHARE) {
        return ReleasePattern.Weekdays(regular, nextOn(regular, last, today))
    }
    // Every so many days, give or take a little.
    if (typical >= 2) {
        val slack = maxOf(1, typical / 4)
        if (gaps.count { abs(it - typical) <= slack } >= gaps.size * STEADY_SHARE) {
            return ReleasePattern.Every(typical, maxOf(last.plusDays(typical.toLong()), today))
        }
    }
    return null
}

/** The pattern in words, for under the novel's status: "New chapters usually Mon and Thu", then on a line of its own "Next about Thursday". */
fun releaseText(pattern: ReleasePattern, today: LocalDate, locale: Locale = Locale.getDefault()): String =
    when (pattern) {
        ReleasePattern.MostDays -> "New chapters most days"
        is ReleasePattern.Weekdays -> "New chapters usually ${weekdays(pattern.days, locale)}\nNext ${whenDue(pattern.next, today, locale)}"
        is ReleasePattern.Every -> "New chapters about ${every(pattern.days)}\nNext ${whenDue(pattern.next, today, locale)}"
    }

/** The pattern alone, for a list of novels: "Mon, Wed and Fri", "Fridays", "Every 3 days", "Most days". */
fun patternText(pattern: ReleasePattern, locale: Locale = Locale.getDefault()): String =
    when (pattern) {
        ReleasePattern.MostDays -> "Most days"
        is ReleasePattern.Weekdays ->
            if (pattern.days.size == 1) "${pattern.days.single().getDisplayName(TextStyle.FULL, locale)}s" else weekdays(pattern.days, locale)
        is ReleasePattern.Every -> every(pattern.days).replaceFirstChar { it.uppercase() }
    }

/** Whether a novel with this pattern has a chapter due on [day]. */
fun ReleasePattern.dueOn(day: LocalDate): Boolean =
    when (this) {
        ReleasePattern.MostDays -> true
        is ReleasePattern.Weekdays -> day.dayOfWeek in days
        is ReleasePattern.Every -> !day.isBefore(next) && ChronoUnit.DAYS.between(next, day) % days == 0L
    }

private fun weekdays(days: List<DayOfWeek>, locale: Locale): String =
    if (days.size == 1) {
        "on ${days.single().getDisplayName(TextStyle.FULL, locale)}s"
    } else {
        val names = days.map { it.getDisplayName(TextStyle.SHORT, locale) }
        names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

private fun every(days: Int): String =
    when {
        days == 7 -> "every week"
        days % 7 == 0 -> "every ${days / 7} weeks"
        else -> "every $days days"
    }

private fun whenDue(next: LocalDate, today: LocalDate, locale: Locale): String {
    val away = ChronoUnit.DAYS.between(today, next)
    return when {
        away <= 0 -> "probably today"
        away == 1L -> "about tomorrow"
        away < 7 -> "about ${next.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}"
        else -> "about ${next.format(DateTimeFormatter.ofPattern("d MMM", locale))}"
    }
}

/** How many of [day] there are from [from] to [to], both included. */
private fun occurrences(day: DayOfWeek, from: LocalDate, to: LocalDate): Int {
    val firstOne = from.plusDays(((day.value - from.dayOfWeek.value + 7) % 7).toLong())
    return if (firstOne > to) 0 else (ChronoUnit.DAYS.between(firstOne, to) / 7 + 1).toInt()
}

/** The first of [days] after [last], or from [today] on when that's gone by. */
private fun nextOn(days: List<DayOfWeek>, last: LocalDate, today: LocalDate): LocalDate {
    var next = maxOf(last.plusDays(1), today)
    while (next.dayOfWeek !in days) next = next.plusDays(1)
    return next
}

/** How many chapters must have the site's date for those dates to go by. */
private const val DATED_SHARE = 0.8

/** Chapters this device saw this soon after the first it saw came with the novel, not after it. */
private const val FIRST_BATCH_MS = 10 * 60 * 1000L

/** The weeks looked at, back from the last chapter. */
private const val WINDOW_DAYS = 56L
private const val MIN_RELEASES = 6
private const val MIN_SPAN = 21L

/** No chapter for this long, or three of its usual gaps if longer, and there's no pattern to go by. */
private const val STALE_DAYS = 14L

/** Chapters on this share of the days, or more, is most days. */
private const val MOST_DAYS = 0.6

/** A day of the week is a release day when this share of its weeks had a chapter on it. */
private const val REGULAR_SHARE = 0.6

/** The release days must have this share of the chapters. */
private const val COVERED_SHARE = 0.75
private const val MAX_WEEKDAYS = 4

/** This share of the gaps must be about the usual one for "every so many days". */
private const val STEADY_SHARE = 0.7
