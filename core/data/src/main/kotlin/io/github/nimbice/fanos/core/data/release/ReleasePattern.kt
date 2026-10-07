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
import java.time.LocalTime
import kotlin.math.ceil

/** When a novel's chapters come out, going by when the last few weeks' did. */
sealed interface ReleasePattern {
    /** On these days of the week, the [next] of them due then. */
    data class Weekdays(val days: List<DayOfWeek>, val next: LocalDate) : ReleasePattern

    /** Most days of the week; [everyDay] when it's nearly every one. */
    data class MostDays(val everyDay: Boolean = false) : ReleasePattern

    /** Every so many [days], the [next] due then. */
    data class Every(val days: Int, val next: LocalDate) : ReleasePattern

    /**
     * Every [shortest] to [longest] days, on no set day: the next could come any day from [from] to [to]. The calendar
     * shows it on [from], an early guess that may well be too early.
     */
    data class Between(val shortest: Int, val longest: Int, val from: LocalDate, val to: LocalDate) : ReleasePattern
}

/** The time of day a novel's chapters come out, in this device's time zone: about one time, or within a window. */
sealed interface ReleaseTime {
    data class At(val time: LocalTime) : ReleaseTime

    data class Window(val from: LocalTime, val to: LocalTime) : ReleaseTime
}

/** Whether the site dates enough of a novel's [chapters] ([dated] of them) for its dates to go by. */
fun siteDated(chapters: Int, dated: Int): Boolean = dated > 0 && dated >= chapters * DATED_SHARE

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
    if (siteDated(chapters, dated)) return published
    val first = firstAdded ?: return emptyList()
    return added.filter { it > first + FIRST_BATCH_MS }
}

/** The time of day [chapters] come out, from the site's own times; null when the site gives none. */
fun releaseTime(chapters: List<Chapter>, zone: ZoneId): ReleaseTime? {
    val dated = chapters.mapNotNull { it.publishedAt }
    return if (siteDated(chapters.size, dated.size)) releaseTime(dated, zone) else null
}

/**
 * The time of day [times] (the site's own) fall at, going by the last eight weeks': about one time when three in four
 * of them are within half an hour of one another, a window when they're within four hours, else null. Null too when
 * the site gives dates without a time (most of them at midnight UTC), or there are too few to go by. When this device
 * first saw a chapter says nothing of its time: it saw it at its next check for new chapters.
 */
fun releaseTime(times: Collection<Long>, zone: ZoneId): ReleaseTime? {
    val last = times.maxOrNull() ?: return null
    val recent = times.filter { it > last - WINDOW_DAYS * DAY_MS }
    if (recent.size < MIN_RELEASES) return null
    if (recent.count { it % DAY_MS == 0L } * 2 >= recent.size) return null
    val minutes = recent.map { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().let { time -> time.hour * 60 + time.minute } }.sorted()
    val need = ceil(minutes.size * TIME_SHARE).toInt()
    // Round the clock, as a window may run past midnight: the shortest that holds three in four of them.
    val around = minutes + minutes.map { it + MINUTES_A_DAY }
    val start = minutes.indices.minBy { around[it + need - 1] - around[it] }
    val width = around[start + need - 1] - around[start]
    return when {
        width <= STEADY_MINUTES -> ReleaseTime.At(clockTime(roundTo(around[start + (need - 1) / 2], 5)))
        width <= WINDOW_MINUTES -> ReleaseTime.Window(clockTime(around[start] / 30 * 30), clockTime((around[start + need - 1] + 29) / 30 * 30))
        else -> null
    }
}

/**
 * The pattern in the [days] chapters came out, as of [today]: the days of the week most weeks have one on, most days,
 * every so many days, or every so many to so many days. Null without a pattern clear enough to go by, or when the last
 * chapter is long past due.
 */
fun releasePattern(days: Collection<LocalDate>, today: LocalDate): ReleasePattern? {
    val all = days.toSortedSet()
    val last = all.lastOrNull() ?: return null
    val recent = all.filter { it > last.minusDays(WINDOW_DAYS) }
    if (recent.size < MIN_RELEASES) return null
    val first = recent.first()
    val span = ChronoUnit.DAYS.between(first, last) + 1
    // A week of chapters most days is enough to call it daily: a novel only just added, its chapters undated, has no more.
    if (span < MIN_SPAN_DAILY) return null
    val gaps = recent.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
    val typical = gaps.sorted()[gaps.size / 2]
    // Long past when the next was due: a pause, not a pattern to go by.
    if (ChronoUnit.DAYS.between(last, today) > maxOf(STALE_DAYS, typical * 3L)) return null
    if (recent.size >= span * MOST_DAYS) return ReleasePattern.MostDays(everyDay = recent.size >= span * EVERY_DAY)
    // Days of the week and steps of so many days take a few weeks to tell.
    if (span < MIN_SPAN) return null
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
    // On no set day, but three in four of the gaps within a week's window: the next any day in it.
    val need = ceil(gaps.size * BETWEEN_SHARE).toInt()
    val sorted = gaps.sorted()
    val start = (0..sorted.size - need).minBy { sorted[it + need - 1] - sorted[it] }
    val shortest = sorted[start]
    val longest = sorted[start + need - 1]
    if (longest > shortest && longest - shortest <= MAX_WINDOW_DAYS) {
        val from = maxOf(last.plusDays(shortest.toLong()), today)
        return ReleasePattern.Between(shortest, longest, from, maxOf(last.plusDays(longest.toLong()), from))
    }
    return null
}

/**
 * The pattern in words, for under the novel's status: "New chapters usually Mon and Thu, around 6 PM", then on a line of
 * its own "Next about Thursday". With no set day, the window: "Next between Monday and Friday, Monday at the earliest".
 */
fun releaseText(
    pattern: ReleasePattern,
    today: LocalDate,
    locale: Locale = Locale.getDefault(),
    time: ReleaseTime? = null,
    is24Hour: Boolean = false,
    perDay: Int = 1,
): String {
    val at = (perDayText(pattern, perDay, short = true)?.let { ", $it" }.orEmpty()) + time?.let { ", " + timeText(it, is24Hour) }.orEmpty()
    return when (pattern) {
        is ReleasePattern.MostDays -> "New chapters ${if (pattern.everyDay) "every day" else "most days"}$at"
        is ReleasePattern.Weekdays -> "New chapters usually ${weekdays(pattern.days, locale)}$at\nNext ${whenDue(pattern.next, today, locale)}"
        is ReleasePattern.Every -> "New chapters about ${every(pattern.days)}$at\nNext ${whenDue(pattern.next, today, locale)}"
        is ReleasePattern.Between ->
            "New chapters every ${pattern.shortest} to ${pattern.longest} days, no set day$at\nNext ${nextBetween(pattern, today, locale)}"
    }
}

/**
 * How many chapters come out at once, when it's more than one: "2 chapters a day" for a novel out every day, else
 * "2 chapters at a time"; [short], "2 a day" and "2 at a time". Null for one.
 */
fun perDayText(pattern: ReleasePattern, perDay: Int, short: Boolean = false): String? {
    if (perDay < 2) return null
    val what = if (short) "$perDay" else "$perDay chapters"
    return if (pattern is ReleasePattern.MostDays && pattern.everyDay) "$what a day" else "$what at a time"
}

/** How many chapters usually come out on a day one does, going by the last eight weeks' [days] (one for each chapter). */
fun chaptersPerDay(days: Collection<LocalDate>): Int {
    val last = days.maxOrNull() ?: return 0
    val counts = days.filter { it > last.minusDays(WINDOW_DAYS) }.groupingBy { it }.eachCount().values.sorted()
    return counts[(counts.size - 1) / 2]
}

/** The time of day in words: "around 6 PM", "between 2 and 5 PM", "between 11 PM and 1 AM"; on a 24-hour clock "around 18:00". */
fun timeText(time: ReleaseTime, is24Hour: Boolean = false): String =
    when (time) {
        is ReleaseTime.At -> "around ${clockText(time.time, is24Hour, meridiem = true)}"
        is ReleaseTime.Window -> {
            // "2 and 5 PM" on the same side of noon; else each with its own.
            val sameHalf = !is24Hour && (time.from.hour < 12) == (time.to.hour < 12)
            "between ${clockText(time.from, is24Hour, meridiem = !sameHalf)} and ${clockText(time.to, is24Hour, meridiem = true)}"
        }
    }

/**
 * For a list, the days a novel with no set day could have its next chapter: "could be Mon to Fri", "could be today, or
 * as late as Fri"; with the dates when the window is a week or more long.
 */
fun windowText(pattern: ReleasePattern.Between, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val dated = ChronoUnit.DAYS.between(pattern.from, pattern.to) >= 6
    fun day(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + if (dated) " ${date.dayOfMonth}" else ""
    return when {
        pattern.from == pattern.to -> "could be ${if (pattern.from == today) "today" else day(pattern.from)}"
        pattern.from == today -> "could be today, or as late as ${day(pattern.to)}"
        else -> "could be ${day(pattern.from)} to ${day(pattern.to)}"
    }
}

/** The pattern alone, for a list of novels: "Mon, Wed and Fri", "Fridays", "Every 3 days", "Every day", "Most days". */
fun patternText(pattern: ReleasePattern, locale: Locale = Locale.getDefault()): String =
    when (pattern) {
        is ReleasePattern.MostDays -> if (pattern.everyDay) "Every day" else "Most days"
        is ReleasePattern.Weekdays ->
            if (pattern.days.size == 1) "${pattern.days.single().getDisplayName(TextStyle.FULL, locale)}s" else weekdays(pattern.days, locale)
        is ReleasePattern.Every -> every(pattern.days).replaceFirstChar { it.uppercase() }
        is ReleasePattern.Between -> "Every ${pattern.shortest} to ${pattern.longest} days"
    }

/** Whether a novel with this pattern has a chapter due on [day]; one with no set day only on its earliest. */
fun ReleasePattern.dueOn(day: LocalDate): Boolean =
    when (this) {
        is ReleasePattern.MostDays -> true
        is ReleasePattern.Weekdays -> day.dayOfWeek in days
        is ReleasePattern.Every -> !day.isBefore(next) && ChronoUnit.DAYS.between(next, day) % days == 0L
        is ReleasePattern.Between -> day == from
    }

/** The next chapter's window in words: "between Monday and Friday, Monday at the earliest". */
private fun nextBetween(pattern: ReleasePattern.Between, today: LocalDate, locale: Locale): String {
    fun day(date: LocalDate): String {
        val away = ChronoUnit.DAYS.between(today, date)
        return when {
            away <= 0 -> "today"
            away == 1L -> "tomorrow"
            away < 7 -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else -> date.format(DateTimeFormatter.ofPattern("d MMM", locale))
        }
    }
    return when {
        pattern.from == pattern.to -> whenDue(pattern.from, today, locale)
        pattern.from == today -> "could be today, or as late as ${day(pattern.to)}"
        else -> "between ${day(pattern.from)} and ${day(pattern.to)}, ${day(pattern.from)} at the earliest"
    }
}

/** A time of day: "6 PM", "6:30 PM", or without the AM/PM; "18:00" on a 24-hour clock. */
private fun clockText(time: LocalTime, is24Hour: Boolean, meridiem: Boolean): String {
    if (is24Hour) return "%02d:%02d".format(time.hour, time.minute)
    val hour = (time.hour + 11) % 12 + 1
    val clock = if (time.minute == 0) "$hour" else "$hour:%02d".format(time.minute)
    return if (meridiem) "$clock ${if (time.hour < 12) "AM" else "PM"}" else clock
}

/** [minutes] into a day (or past it) as a time of day. */
private fun clockTime(minutes: Int): LocalTime = LocalTime.of(Math.floorMod(minutes, MINUTES_A_DAY) / 60, Math.floorMod(minutes, MINUTES_A_DAY) % 60)

private fun roundTo(minutes: Int, step: Int): Int = (minutes + step / 2) / step * step

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

/** A week is enough to tell a novel out most days; the other patterns need [MIN_SPAN]. */
private const val MIN_SPAN_DAILY = 7L

/** No chapter for this long, or three of its usual gaps if longer, and there's no pattern to go by. */
private const val STALE_DAYS = 14L

/** Chapters on this share of the days, or more, is most days; on this share, every day. */
private const val MOST_DAYS = 0.6
private const val EVERY_DAY = 0.9

/** A day of the week is a release day when this share of its weeks had a chapter on it. */
private const val REGULAR_SHARE = 0.6

/** The release days must have this share of the chapters. */
private const val COVERED_SHARE = 0.75
private const val MAX_WEEKDAYS = 4

/** This share of the gaps must be about the usual one for "every so many days". */
private const val STEADY_SHARE = 0.7

/** For "every so many to so many days": this share of the gaps within a window of at most so many days. */
private const val BETWEEN_SHARE = 0.75
private const val MAX_WINDOW_DAYS = 6

/** A time of day when this share of the chapters came out within [STEADY_MINUTES] of one another, a window within [WINDOW_MINUTES]. */
private const val TIME_SHARE = 0.75
private const val STEADY_MINUTES = 30
private const val WINDOW_MINUTES = 240

private const val MINUTES_A_DAY = 24 * 60
private const val DAY_MS = 24 * 60 * 60 * 1000L
