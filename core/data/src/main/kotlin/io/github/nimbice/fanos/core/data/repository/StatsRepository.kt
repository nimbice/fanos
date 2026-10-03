package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.database.TransactionRunner
import io.github.nimbice.fanos.core.database.dao.StatsDao
import io.github.nimbice.fanos.core.database.entity.ReadMarkRow
import io.github.nimbice.fanos.core.database.entity.ReadingTimeEntity
import io.github.nimbice.fanos.core.model.DayStats
import io.github.nimbice.fanos.core.model.NovelStats
import io.github.nimbice.fanos.core.model.ReadingStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reading stats: time spent reading or listening, kept by day and novel from when it was first counted, and
 * chapters read, from their read marks.
 */
@Singleton
class StatsRepository @Inject constructor(private val stats: StatsDao, private val database: TransactionRunner) {

    /** Adds [seconds] of reading [novelId] to today. */
    suspend fun addTime(novelId: Long, seconds: Int) {
        val day = LocalDate.now().toEpochDay()
        database.transaction {
            stats.startDay(day, novelId)
            stats.addTime(day, novelId, seconds)
        }
    }

    fun observe(): Flow<ReadingStats> =
        combine(stats.observeTime(), stats.observeReadMarks()) { time, marks -> ReadingStatsMath.of(time, marks, LocalDate.now(), ZoneId.systemDefault()) }
            .map { found ->
                // The novels' titles and covers as the library shows them.
                val faces = if (found.byNovel.isEmpty()) emptyMap() else stats.faces(found.byNovel.map { it.novelId }).associateBy { it.id }
                found.copy(byNovel = found.byNovel.mapNotNull { novel -> faces[novel.novelId]?.let { novel.copy(title = it.title, coverUrl = it.coverUrl) } })
            }.flowOn(Dispatchers.Default)
}

/** The stats worked out from the stored time and read marks; apart, to be tested. */
internal object ReadingStatsMath {

    fun of(time: List<ReadingTimeEntity>, marks: List<ReadMarkRow>, today: LocalDate, zone: ZoneId): ReadingStats {
        // Chapters marked read together (a novel's earlier chapters marked at once, an import) weren't read one by one
        // that moment: only marks no more than two of a novel's chapters share count as chapters read.
        val reads =
            marks.groupBy { it.novelId to it.readAt }.values.filter { it.size <= BULK }.flatten()
                .map { it.novelId to Instant.ofEpochMilli(it.readAt).atZone(zone).toLocalDate() }
        val chaptersByDay = reads.groupingBy { it.second }.eachCount()
        val secondsByDay = time.groupBy { LocalDate.ofEpochDay(it.day) }.mapValues { (_, rows) -> rows.sumOf { it.seconds.toLong() } }
        fun active(day: LocalDate) = (chaptersByDay[day] ?: 0) > 0 || (secondsByDay[day] ?: 0L) >= ACTIVE_SECONDS

        var day = if (active(today)) today else today.minusDays(1)
        var streak = 0
        while (active(day)) {
            streak++
            day = day.minusDays(1)
        }
        // The longest run of such days there's been, this one too.
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (date in (chaptersByDay.keys + secondsByDay.keys).filter(::active).distinct().sorted()) {
            run = if (previous != null && previous.plusDays(1) == date) run + 1 else 1
            longest = maxOf(longest, run)
            previous = date
        }

        fun thisMonth(date: LocalDate) = date.year == today.year && date.month == today.month
        val monthReads = reads.filter { thisMonth(it.second) }
        val monthTime = time.filter { thisMonth(LocalDate.ofEpochDay(it.day)) }
        val chaptersByNovel = monthReads.groupingBy { it.first }.eachCount()
        val secondsByNovel = monthTime.groupBy { it.novelId }.mapValues { (_, rows) -> rows.sumOf { it.seconds.toLong() } }
        val byNovel =
            (chaptersByNovel.keys + secondsByNovel.keys)
                .map { id -> NovelStats(id, "", null, chaptersByNovel[id] ?: 0, secondsByNovel[id] ?: 0L) }
                .sortedWith(compareByDescending<NovelStats> { it.seconds }.thenByDescending { it.chapters })
                .take(TOP)

        return ReadingStats(
            streak = streak,
            longestStreak = maxOf(longest, streak),
            chaptersThisMonth = monthReads.size,
            secondsThisMonth = monthTime.sumOf { it.seconds.toLong() },
            week = (6 downTo 0).map { back -> today.minusDays(back.toLong()).let { DayStats(it, secondsByDay[it] ?: 0L, chaptersByDay[it] ?: 0) } },
            byNovel = byNovel,
        )
    }

    /** The most of a novel's chapters a moment's read marks may have and still count as read one by one. */
    private const val BULK = 2

    /** Reading that makes a day count for the streak: a minute, or a chapter read. */
    private const val ACTIVE_SECONDS = 60L

    private const val TOP = 10
}
