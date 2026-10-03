package io.github.nimbice.fanos.core.data.extension

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.PathClassLoader
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.data.copyAtMost
import io.github.nimbice.fanos.core.data.source.sourceInfo
import io.github.nimbice.fanos.core.datastore.SettingsDataSource
import io.github.nimbice.fanos.core.model.ExtensionInfo
import io.github.nimbice.fanos.core.model.ExtensionStatus
import io.github.nimbice.fanos.source.api.Extensions
import io.github.nimbice.fanos.source.api.NovelSource
import io.github.nimbice.fanos.source.api.SourceFactory
import io.github.nimbice.fanos.source.api.SourceHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The extensions the app reads from. An extension is an APK that is never installed on the phone: the app keeps
 * it among its own files, checks who signed it, and loads its classes, and the sources it makes join the others.
 * Only extensions signed with a key the reader has trusted are loaded, and each is loaded with the app's own
 * classes as its parent, so it shares the app's source API and libraries rather than bringing its own.
 */
@Singleton
class ExtensionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    // Made on the loading thread when first needed: the app starts without building a network client.
    private val http: Provider<SourceHttp>,
    private val settings: SettingsDataSource,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val reader = ApkReader(context.packageManager)
    private val dir = File(context.filesDir, "extensions")
    private val pickedDir = File(context.cacheDir, "picked-extensions")
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    /** Null until the installed extensions have been loaded once. */
    private val state = MutableStateFlow<Loaded?>(null)

    /** The installed extensions, working or not, once they've been loaded. */
    val extensions: Flow<List<ExtensionInfo>> =
        state.filterNotNull().map { loaded -> loaded.installed.map { it.info() }.sortedBy { it.name.lowercase() } }

    /** The working extensions' sources, by id, once they've been loaded. */
    val sources: Flow<Map<String, NovelSource>> = state.filterNotNull().map { it.sources }

    /** The keys the reader has trusted, besides the app's own; each can be forgotten again. */
    val trustedSigners: Flow<Set<String>> = settings.trustedSigners.map { it - BUILT_IN_SIGNERS }

    /** Starts loading the installed extensions, unless that has started already. */
    fun start() {
        if (started.compareAndSet(false, true)) scope.launch(io) { mutex.withLock { state.value = scan() } }
    }

    /** The working extensions' sources, by id, waiting for them to load the first time. */
    suspend fun loadedSources(): Map<String, NovelSource> {
        start()
        return state.filterNotNull().first().sources
    }

    /**
     * Copies the files the reader picked and reads them, installing nothing: the reader may yet be asked to
     * trust a new key. What comes back is either ready to [install] or says why it can't be.
     */
    suspend fun inspect(files: List<Uri>): List<PickedExtension> =
        withContext(io) {
            loadedSources()
            val trusted = settings.trustedSigners.first() + BUILT_IN_SIGNERS
            val installed = state.value?.installed.orEmpty()
            pickedDir.mkdirs()
            files.map { uri -> inspect(uri, trusted, installed) }
        }

    private fun inspect(uri: Uri, trusted: Set<String>, installed: List<Installed>): PickedExtension {
        val name = displayName(uri)
        val copy = File(pickedDir, "${UUID.randomUUID()}.apk")
        val problem =
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw ExtensionFileException("Couldn't open the file")
                input.use { from -> copy.outputStream().use { to -> from.copyAtMost(to, MAX_FILE_BYTES) { ExtensionFileException("Too large to be an extension") } } }
                val apk = reader.read(copy)
                val current = installed.firstOrNull { it.apk?.packageName == apk.packageName }?.apk
                when {
                    apk.apiLevel > Extensions.API_LEVEL -> "Needs a newer version of Fanos"
                    apk.apiLevel < Extensions.MIN_API_LEVEL -> "Made for an older version of Fanos"
                    // A new version must come from whoever made the installed one: a key can't be swapped under it.
                    current != null && apk.signer != current.signer -> "Signed with a key other than the installed version's. Remove the installed one first to change keys"
                    current != null && apk.versionCode < current.versionCode -> "Older than the installed version, ${current.versionName}"
                    else -> return PickedExtension.Ready(apk, copy, current?.versionCode, apk.signer in trusted)
                }
            } catch (e: ExtensionFileException) {
                e.message
            } catch (e: IOException) {
                "Couldn't read the file"
            }
        copy.delete()
        return PickedExtension.Refused(name, problem)
    }

    /** Trusts [signers]: extensions signed with them install and load from now on. */
    suspend fun trust(signers: Set<String>) {
        settings.trustSigners(signers)
    }

    /**
     * Installs [picked], replacing any versions installed, and loads them. Each must be signed with a trusted key:
     * the reader is asked about a new key before this, and one refused means its extensions aren't installed.
     */
    suspend fun install(picked: List<PickedExtension.Ready>) {
        withContext(io) {
            mutex.withLock {
                val trusted = settings.trustedSigners.first() + BUILT_IN_SIGNERS
                dir.mkdirs()
                for (extension in picked) {
                    if (extension.apk.signer !in trusted) {
                        extension.copy.delete()
                        continue
                    }
                    val target = File(dir, "${extension.apk.packageName}.apk")
                    val fresh = File(dir, "${extension.apk.packageName}.apk.new")
                    extension.copy.copyTo(fresh, overwrite = true)
                    extension.copy.delete()
                    // Android refuses to load code from a file the app could still change.
                    fresh.setReadOnly()
                    target.delete()
                    if (!fresh.renameTo(target)) throw IOException("Couldn't install ${extension.apk.name}")
                }
                state.value = scan()
            }
        }
    }

    /** Lets go of picked files that won't be installed. */
    fun discard(picked: List<PickedExtension.Ready>) {
        picked.forEach { it.copy.delete() }
    }

    /** Removes the extension [id] (its package name). Novels read from its sources stay in the library. */
    suspend fun uninstall(id: String) {
        withContext(io) {
            mutex.withLock {
                File(dir, "$id.apk").delete()
                state.value = scan()
            }
        }
    }

    /** Forgets [signer]: extensions signed with it stay installed but stop loading, until it's trusted again. */
    suspend fun forget(signer: String) {
        withContext(io) {
            mutex.withLock {
                settings.forgetSigner(signer)
                state.value = scan()
            }
        }
    }

    /** Loads every installed extension afresh. */
    private suspend fun scan(): Loaded {
        val trusted = settings.trustedSigners.first() + BUILT_IN_SIGNERS
        // Left by an install the app was closed in the middle of.
        dir.listFiles { file -> file.name.endsWith(".apk.new") }?.forEach { it.delete() }
        pickedDir.listFiles()?.forEach { it.delete() }
        val installed = mutableListOf<Installed>()
        val sources = LinkedHashMap<String, NovelSource>()
        for (file in dir.listFiles { file -> file.isFile && file.name.endsWith(".apk") }.orEmpty().sortedBy { it.name }) {
            val extension = load(file, trusted, sources.keys)
            installed += extension
            extension.sources.forEach { sources[it.id] = it }
        }
        return Loaded(installed, sources)
    }

    private fun load(file: File, trusted: Set<String>, taken: Set<String>): Installed {
        val apk =
            try {
                reader.read(file)
            } catch (e: ExtensionFileException) {
                return Installed(file, null, ExtensionStatus.Broken, problem = e.message)
            }
        val status =
            when {
                apk.signer !in trusted -> ExtensionStatus.Untrusted
                apk.apiLevel > Extensions.API_LEVEL -> ExtensionStatus.NeedsNewerApp
                apk.apiLevel < Extensions.MIN_API_LEVEL -> ExtensionStatus.Outdated
                else -> null
            }
        if (status != null) return Installed(file, apk, status)
        return try {
            file.setReadOnly()
            val loader = PathClassLoader(file.absolutePath, SourceFactory::class.java.classLoader)
            val factory = loader.loadClass(apk.factory).getDeclaredConstructor().newInstance() as SourceFactory
            val made = factory.create(http.get())
            val clash = made.firstOrNull { it.id in taken }
            if (clash != null) {
                Installed(file, apk, ExtensionStatus.Broken, problem = "Another extension already reads from ${clash.name}")
            } else {
                Installed(file, apk, ExtensionStatus.Working, made)
            }
        } catch (e: Exception) {
            Installed(file, apk, ExtensionStatus.Broken, problem = e.message ?: e.javaClass.simpleName)
        } catch (e: LinkageError) {
            // Built against classes this version of the app doesn't have.
            Installed(file, apk, ExtensionStatus.Broken, problem = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "File"

    private class Loaded(val installed: List<Installed>, val sources: Map<String, NovelSource>)

    /** An extension file the app keeps: what it says about itself (null when it can't be read) and how loading it went. */
    private class Installed(
        val file: File,
        val apk: ExtensionApk?,
        val status: ExtensionStatus,
        val sources: List<NovelSource> = emptyList(),
        val problem: String? = null,
    ) {
        fun info() =
            ExtensionInfo(
                id = apk?.packageName ?: file.nameWithoutExtension,
                name = apk?.name ?: file.nameWithoutExtension,
                versionName = apk?.versionName.orEmpty(),
                versionCode = apk?.versionCode ?: 0,
                sources = sources.map { it.sourceInfo() },
                status = status,
                problem = problem,
            )
    }

    private companion object {
        /** Extensions are tens of kilobytes; this leaves room and stops a picked video from being copied whole. */
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
    }
}

/** A file the reader picked to install. */
sealed interface PickedExtension {
    /** An extension that can be installed: new, or a version at least as new as the one installed. */
    class Ready(
        val apk: ExtensionApk,
        internal val copy: File,
        /** The version installed now, when this replaces one. */
        val installedVersionCode: Long?,
        /** Whether its key is trusted already; if not, the reader is asked first. */
        val signerTrusted: Boolean,
    ) : PickedExtension

    /** A file that can't be installed, and why. */
    class Refused(val fileName: String, val reason: String) : PickedExtension
}

/** The key the Fanos extensions repository signs with: trusted from the start, so its extensions install without asking. */
private val BUILT_IN_SIGNERS = setOf("F3E8B2DEB7A7162E3B1E3151DC8D15E84104556EA4D324ACE8646B39D8A5AAAA")
