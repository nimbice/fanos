package io.github.nimbice.fanos.core.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.data.release.nearRegularCheck
import io.github.nimbice.fanos.core.data.release.nextProbe
import io.github.nimbice.fanos.core.data.release.nextReleaseCheck
import io.github.nimbice.fanos.core.data.release.regularCheckSoon
import io.github.nimbice.fanos.core.data.release.releasePattern
import io.github.nimbice.fanos.core.data.repository.RecentRepository
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.model.UpdateInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks a novel on its own just after its next chapter is expected out ([nextReleaseCheck]): for the novels whose
 * chapters come out on known days at a known time of day, rather than waiting for the next library update; a minute
 * after, then again 5, 10, 30, 30 and 30 minutes on while it isn't there, but not within an hour of the scheduled
 * library update, which looks anyway. While a novel's days are known but its time isn't (to half an hour), as the
 * library updates, every few hours at about the same times, can never tell it, it's probed to find it ([nextProbe]). One check waits at a time, the soonest, worked out afresh whenever no library check is running: at app
 * start, and after each one (this one too) finishes, when what it found may have moved the novel's next chapter on.
 * Off with the library updates.
 */
@Singleton
class ReleaseCheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recent: RecentRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val workManager get() = WorkManager.getInstance(context)

    fun start() {
        scope.launch {
            val on = settings.appSettings.map { it.libraryUpdateInterval != UpdateInterval.Off }.distinctUntilChanged()
            // Not while one runs: what it finds changes when the next is due, and replacing this check while it runs would stop it.
            val checking =
                workManager.getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(LibraryUpdateScheduler.WORK_NAMES + NAME))
                    .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
                    .distinctUntilChanged()
            combine(on, checking, ::Pair).collectLatest { (on, running) ->
                when {
                    !on -> workManager.cancelUniqueWork(NAME)
                    !running -> schedule()
                }
            }
        }
    }

    private suspend fun schedule() {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(clock.now()).atZone(zone)
        // When the scheduled library update runs next, and how often: none known, nothing is left to it.
        val regular =
            workManager.getWorkInfosForUniqueWorkFlow(LibraryUpdateScheduler.PERIODIC).first()
                .firstOrNull { it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis != Long.MAX_VALUE }
        val every = regular?.periodicityInfo?.repeatIntervalMillis ?: 0
        val nearRegular: (ZonedDateTime) -> Boolean =
            if (regular == null) { { false } } else { { at -> nearRegularCheck(at, regular.nextScheduleTimeMillis, every) } }
        val regularSoon: (ZonedDateTime, ZonedDateTime) -> Boolean =
            if (regular == null) { { _, _ -> false } } else { { at, until -> regularCheckSoon(at, until, regular.nextScheduleTimeMillis, every) } }
        val checks =
            recent.releaseDays(zone).mapNotNull { novel ->
                val pattern = releasePattern(novel.days, now.toLocalDate()) ?: return@mapNotNull null
                val release = nextReleaseCheck(pattern, novel.time, novel.days, now, nearRegular)
                // Pinning the time down, while the site's own times don't say it; not while the checks are failing,
                // and not just before a library update, which does as well.
                val probe =
                    if (novel.timeKnown || novel.checkFailed) null
                    else nextProbe(pattern, novel.checkRange, novel.days, novel.lastChecked, now, regularSoon)
                listOfNotNull(release, probe).minByOrNull { it.toInstant() }?.let { novel.novelId to it.toInstant().toEpochMilli() }
            }.toMap()
        val soonest = soonestChecks(checks)
        if (soonest == null) {
            workManager.cancelUniqueWork(NAME)
            return
        }
        val (at, novelIds) = soonest
        val request =
            OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
                .setInitialDelay((at - clock.now()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(LibraryUpdateWorker.KEY_NOTIFY to true, LibraryUpdateWorker.KEY_NOVEL_IDS to novelIds.toLongArray()))
                .build()
        workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val NAME = "release-check"
    }
}

/**
 * The soonest of [checks] (each novel's next check, epoch milliseconds) and the novels due then: the ones within
 * [together] of it are checked with it, rather than each a few seconds apart; no more, as a check much early would
 * come before a chapter due a minute before it. Null with none.
 */
internal fun soonestChecks(checks: Map<Long, Long>, together: Long = TOGETHER_MS): Pair<Long, Set<Long>>? {
    val soonest = checks.values.minOrNull() ?: return null
    return soonest to checks.filterValues { it <= soonest + together }.keys
}

private const val TOGETHER_MS = 60 * 1000L
