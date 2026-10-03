package io.github.nimbice.fanos.core.data.extension

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import io.github.nimbice.fanos.source.api.Extensions
import java.io.File
import java.security.MessageDigest

/** What an extension's APK says about itself, and who signed it. */
data class ExtensionApk(
    val packageName: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val apiLevel: Int,
    val factory: String,
    /** SHA-256 of the signing certificate, in upper-case hex: the key the reader is asked to trust. */
    val signer: String,
)

/** A file that isn't an extension Fanos can use; [message] says why, for the reader. */
class ExtensionFileException(override val message: String) : Exception(message)

/**
 * Reads extension APKs with Android's own package parser, which also verifies their signatures: a file whose
 * signature doesn't check out comes back without signers, and is refused.
 */
internal class ApkReader(private val packageManager: PackageManager) {

    fun read(file: File): ExtensionApk {
        val path = file.absolutePath
        val info = archiveInfo(path) ?: throw ExtensionFileException("Not an Android package")
        val app = info.applicationInfo ?: throw ExtensionFileException(NOT_AN_EXTENSION)
        // The label is a resource, read from the file itself.
        app.sourceDir = path
        app.publicSourceDir = path
        val meta = app.metaData ?: throw ExtensionFileException(NOT_AN_EXTENSION)
        val factory = meta.getString(Extensions.META_FACTORY) ?: throw ExtensionFileException(NOT_AN_EXTENSION)
        val apiLevel = meta.getInt(Extensions.META_API_LEVEL, 0).takeIf { it > 0 } ?: throw ExtensionFileException(NOT_AN_EXTENSION)
        val signers = signersOf(info).map { sha256(it.toByteArray()) }.sorted()
        if (signers.isEmpty()) throw ExtensionFileException("Not signed, or its signature doesn't check out")
        return ExtensionApk(
            packageName = info.packageName,
            name = app.loadLabel(packageManager).toString(),
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            versionName = info.versionName.orEmpty(),
            apiLevel = apiLevel,
            factory = factory,
            signer = signers.joinToString("+"),
        )
    }

    private fun archiveInfo(path: String): PackageInfo? {
        val flags =
            PackageManager.GET_META_DATA or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(path, flags)
        }
    }

    private fun signersOf(info: PackageInfo): List<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty().toList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().toList()
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    private companion object {
        const val NOT_AN_EXTENSION = "Not a Fanos extension"
    }
}
