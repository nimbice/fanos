package io.github.nimbice.fanos.core.model

import java.time.LocalDate

/** How the reading has gone: the streak, this month, this week day by day, and this month novel by novel. */
data class ReadingStats(
    /** Days in a row, to today (or yesterday, while today has none yet), with some reading. */
    val streak: Int = 0,
    /** The most days in a row with some reading there have been. */
    val longestStreak: Int = 0,
    val chaptersThisMonth: Int = 0,
    val secondsThisMonth: Long = 0,
    /** The last seven days, today last. */
    val week: List<DayStats> = emptyList(),
    /** This month's novels, the most read first. */
    val byNovel: List<NovelStats> = emptyList(),
) {
    val isEmpty: Boolean get() = streak == 0 && chaptersThisMonth == 0 && secondsThisMonth == 0L && week.all { it.seconds == 0L && it.chapters == 0 }
}

data class DayStats(val date: LocalDate, val seconds: Long, val chapters: Int)

data class NovelStats(val novelId: Long, val title: String, val coverUrl: String?, val chapters: Int, val seconds: Long)
