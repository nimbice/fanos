package io.github.nimbice.fanos.core.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a fetched build to Android's package installer, once it's shown to be this app, the build it
 * claims to be, and signed with the same key. The first update asks the reader to confirm; after that,
 * Android 12 and later install the app's own updates without asking.
 */
@Singleton
class UpdateInstaller @Inject constructor(@ApplicationContext private val context: Context) {

    /** Why [apk] mustn't be installed as [update], or null when it's fine. */
    fun problemWith(apk: File, update: AvailableUpdate): String? {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, flags) ?: return "The download isn't an app. Try again."
        return when {
            archive.packageName != context.packageName -> "The download is another app, ${archive.packageName}."
            PackageInfoCompat.getLongVersionCode(archive) != update.versionCode -> "The download isn't build ${update.versionName}."
            !sameSigner(archive) -> "The download is signed with another key, so Android wouldn't install it over this app."
            else -> null
        }
    }

    fun install(apk: File, update: AvailableUpdate) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
            }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(context, InstallResultReceiver::class.java).putExtra(EXTRA_VERSION_NAME, update.versionName)
            val callback = PendingIntent.getBroadcast(context, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(callback.intentSender)
        }
    }

    private fun sameSigner(archive: PackageInfo): Boolean {
        val installed = context.packageManager.getPackageInfo(context.packageName, signingFlags())
        return signers(archive).isNotEmpty() && signers(archive) == signers(installed)
    }

    @Suppress("DEPRECATION")
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
        } else {
            info.signatures.orEmpty().map { it.toCharsString() }.toSet()
        }

    internal companion object {
        const val EXTRA_VERSION_NAME = "io.github.nimbice.fanos.update.VERSION_NAME"
    }
}

/**
 * Android's package installer reports here. Asked to confirm, it's shown straight away while the app is
 * in front, else as a notification to tap; done, it says so, since the app was closed to be replaced.
 */
@AndroidEntryPoint
class InstallResultReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: Updater

    @Inject lateinit var notifier: UpdateNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val versionName = intent.getStringExtra(UpdateInstaller.EXTRA_VERSION_NAME).orEmpty()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm =
                    (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                val inFront = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (!inFront || runCatching { context.startActivity(confirm) }.isFailure) notifier.confirm(versionName, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> notifier.updated(versionName)
            // Turned down at the confirmation: back to offering it.
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.installFailed("The update wasn't installed.")
            else -> updater.installFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Android didn't install the update: $it" } ?: "Android didn't install the update (status $status).")
        }
    }
}
