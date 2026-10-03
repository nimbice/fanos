package io.github.nimbice.fanos.core.updater

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.datastore.SettingsDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A build of the app on offer, as its update.json describes it. */
data class AvailableUpdate(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val size: Long,
    val sha256: String,
    /** The APK's address in the repository's API. */
    internal val apkUrl: String,
)

/** Where the updater is. */
sealed interface UpdateState {
    /** No public build published yet, and no key to read private builds with. */
    data object NotSetUp : UpdateState

    /** Nothing newer than this build; [checkedAt] is when that was last found, null when never. */
    data class UpToDate(val checkedAt: Long?) : UpdateState

    data object Checking : UpdateState

    data class Available(val update: AvailableUpdate) : UpdateState

    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState

    /** Handed to Android's package installer, which may ask the reader to confirm. */
    data class Installing(val update: AvailableUpdate) : UpdateState

    data class Failed(val message: String) : UpdateState
}

/**
 * Keeps the app up to date from its builds on GitHub: looks for a newer one (daily and when the app
 * opens, or when asked), and on the reader's word fetches it, checks it's whole and signed as this app
 * is, and has Android install it. Nothing is fetched until the reader chooses to install.
 */
@Singleton
class Updater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsDataSource,
    private val notifier: UpdateNotifier,
    private val installer: UpdateInstaller,
    client: OkHttpClient,
    private val clock: Clock,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val releases = GitHubReleases(client, io)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.UpToDate(null))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Whether a key for private builds is set. */
    val hasKey: Flow<Boolean> = settings.updates.map { it.token != null }

    /** Shows what's known from the last check, as the app starts. */
    suspend fun restore() {
        val record = settings.updates.first()
        if (_state.value is UpdateState.UpToDate) _state.value = if (record.checkedAt == null) UpdateState.NotSetUp else UpdateState.UpToDate(record.checkedAt)
    }

    /**
     * Whether it's been a while since the last check, so opening the app should look again. A last check
     * later than now (the clock was put back) counts as long ago.
     */
    suspend fun checkDue(): Boolean {
        val checkedAt = settings.updates.first().checkedAt
        return checkedAt == null || clock.now() - checkedAt !in 0..RECHECK_MS
    }

    /** Sets the key builds are read with, and looks for one straight away; null forgets it. */
    suspend fun setToken(token: String?) {
        settings.setUpdatesToken(token?.trim()?.ifEmpty { null })
        if (token.isNullOrBlank()) _state.value = UpdateState.NotSetUp else check()
    }

    /**
     * Looks for a build newer than this one: among the public releases, or the private builds when a key is set. A
     * background check ([quietly]) announces each build once, with a notification; one the reader asked for only
     * shows its answer.
     */
    suspend fun check(quietly: Boolean = false): UpdateState {
        val record = settings.updates.first()
        val token = record.token
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return _state.value
        _state.value = UpdateState.Checking
        val result =
            try {
                val latest = if (token != null) releases.latest(token) else releases.latestPublic()
                settings.updatesChecked(clock.now())
                when {
                    latest == null && token == null -> UpdateState.NotSetUp
                    latest != null && latest.versionCode > installedVersion() -> UpdateState.Available(latest)
                    else -> UpdateState.UpToDate(clock.now())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                UpdateState.Failed(e.message.orEmpty())
            } catch (e: IOException) {
                UpdateState.Failed("Couldn't reach GitHub. Check your connection.")
            }
        _state.value = result
        if (quietly && result is UpdateState.Available && record.notifiedVersion != result.update.versionCode) {
            notifier.available(result.update)
            settings.updateNotified(result.update.versionCode)
        }
        return result
    }

    /** Fetches and installs the latest build, in the background so it carries on if the app is left. */
    fun install() {
        notifier.dismissAvailable()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(INSTALL_WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<UpdateInstallWorker>().build())
    }

    fun cancelInstall() {
        WorkManager.getInstance(context).cancelUniqueWork(INSTALL_WORK)
    }

    /** The install worker's job: the latest build, fetched, checked and handed to Android. */
    internal suspend fun downloadAndInstall(): Boolean =
        mutex.withLock {
            val token = settings.updates.first().token
            val update = (check() as? UpdateState.Available)?.update ?: return false
            val file = File(context.cacheDir, "updates/fanos-update.apk").apply { parentFile?.mkdirs() }
            try {
                _state.value = UpdateState.Downloading(update, 0f)
                releases.download(update.apkUrl, token, file, update.size) { progress -> _state.value = UpdateState.Downloading(update, progress) }
                val problem = installer.problemWith(file, update) ?: if (!file.sha256().equals(update.sha256, ignoreCase = true)) "The download was damaged. Try again." else null
                if (problem != null) {
                    fail(problem)
                    return false
                }
                _state.value = UpdateState.Installing(update)
                installer.install(file, update)
                true
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(update)
                throw e
            } catch (e: UpdateException) {
                fail(e.message.orEmpty())
                false
            } catch (e: IOException) {
                fail("The download stopped. Check your connection and try again.")
                false
            } finally {
                file.delete()
            }
        }

    /** What Android's package installer made of the update, from [InstallResultReceiver]. */
    internal fun installFailed(message: String) = fail(message)

    private fun fail(message: String) {
        _state.value = UpdateState.Failed(message)
        notifier.failed(message)
    }

    private fun installedVersion(): Long = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    private companion object {
        const val INSTALL_WORK = "app-update-install"

        /** Opening the app looks again after this long. */
        const val RECHECK_MS = 60 * 60 * 1000L
    }
}
