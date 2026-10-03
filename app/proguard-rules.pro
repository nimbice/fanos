# Extensions are compiled on their own and linked against the app's classes by name when they load, so the
# source API and the libraries the app provides to them survive shrinking whole and keep their names.
-keep class io.github.nimbice.fanos.source.api.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class org.jsoup.** { *; }

# The speech engine's native code finds its Kotlin classes and fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Shrink, but keep names: a crash's trace then reads as the source does, with no mapping file to keep for each release.
-dontobfuscate
