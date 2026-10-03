package io.github.nimbice.fanos.core.data.readinglist

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** When the sites' reading lists are told what changed: soon after it does, or now when the reader asks. */
@Singleton
class ReadingListScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    /** After a pause, so reading on, or rearranging the library, sends its changes together. */
    fun syncSoon() = enqueue(SOON, everything = emptySet(), delaySeconds = SOON_SECONDS)

    /** Every library novel of [sourceId] put on its lists now, whatever the site was told before. */
    fun syncNow(sourceId: String) = enqueue("$NOW:$sourceId", everything = setOf(sourceId), delaySeconds = 0)

    private fun enqueue(name: String, everything: Set<String>, delaySeconds: Long) {
        val request =
            OneTimeWorkRequestBuilder<ReadingListWorker>()
                .setInputData(workDataOf(ReadingListWorker.KEY_EVERYTHING to everything.toTypedArray()))
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val SOON = "reading-lists"
        const val NOW = "reading-lists-now"
        const val SOON_SECONDS = 30L
    }
}
