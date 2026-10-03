package io.github.nimbice.fanos.core.common

/**
 * Which app and version this is, for the services the app names itself to: the dictionaries, Open Library and the
 * voice download. (The sites read through extensions see the device's browser instead, as the security checks they
 * run expect.)
 */
data class AppIdentity(val versionName: String) {
    /** As the app names itself, with where to find it, as Wikimedia and Open Library ask of automated callers. */
    val userAgent: String get() = "Fanos/$versionName (+$HOMEPAGE)"

    companion object {
        /** Where the app's source and builds are published. */
        const val HOMEPAGE = "https://github.com/nimbice/fanos"
    }
}
