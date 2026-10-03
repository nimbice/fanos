package io.github.nimbice.fanos.core.data.readinglist

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Tells the sites' reading lists what changed in the library; tries again later when a site can't be reached. */
@HiltWorker
class ReadingListWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: ReadingListSync,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val everything = inputData.getStringArray(KEY_EVERYTHING)?.toSet().orEmpty()
        return when {
            sync.run(everything) -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            // The next change in the library, or Sync, tries again.
            else -> Result.failure()
        }
    }

    internal companion object {
        /** The sources to tell everything, not only what changed. */
        const val KEY_EVERYTHING = "everything"
        const val MAX_ATTEMPTS = 5
    }
}
