package io.github.nimbice.fanos.source.api

/**
 * An extension's way in. An extension names its factory in its manifest ([Extensions.META_FACTORY]); the app
 * makes one with the no-argument constructor and asks it for the extension's sources.
 */
interface SourceFactory {
    fun create(http: SourceHttp): List<NovelSource>
}

/**
 * The contract between the app and its extensions. An extension is an APK built against this library that is
 * never installed on the phone as an app: the app keeps it privately, checks who signed it, and loads its
 * classes. The app provides this library, Kotlin's standard library, kotlinx.coroutines, kotlinx.serialization
 * and jsoup, so an extension must not bundle them.
 */
object Extensions {
    /**
     * This library's level, recorded in each extension ([META_API_LEVEL]). Every addition raises it, and an
     * extension built against a level needs an app at that level or later, as with Android's API levels.
     */
    const val API_LEVEL = 8

    /** The oldest level the app still loads. It goes up only when a change breaks what older extensions use. */
    const val MIN_API_LEVEL = 1

    /** Manifest `<meta-data>` on the extension's `<application>`: the [API_LEVEL] it was built against. */
    const val META_API_LEVEL = "fanos.extension.api"

    /** Manifest `<meta-data>` on the extension's `<application>`: the fully qualified name of its [SourceFactory]. */
    const val META_FACTORY = "fanos.extension.factory"
}
