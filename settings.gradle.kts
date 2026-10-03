pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// sherpa-onnx (offline text-to-speech, for Listening's natural voices) isn't published to Maven: its Android library,
// from the project's own release, is kept in a local repository, fetched once and checked against its published SHA-256.
val thirdParty = file("third_party/maven")
run {
    val version = "1.13.8"
    val sha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
    val dir = File(thirdParty, "com/k2fsa/sherpa/onnx/sherpa-onnx/$version")
    val aar = File(dir, "sherpa-onnx-$version.aar")
    if (!aar.exists()) {
        dir.mkdirs()
        val part = File(dir, "download.part")
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-static-link-onnxruntime-$version.aar"
        java.net.URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(part.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == sha256) { "sherpa-onnx $version isn't the expected file (SHA-256 $digest)" }
        check(part.renameTo(aar)) { "Couldn't keep $aar" }
        File(dir, "sherpa-onnx-$version.pom").writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>com.k2fsa.sherpa.onnx</groupId>" +
                "<artifactId>sherpa-onnx</artifactId><version>$version</version><packaging>aar</packaging></project>",
        )
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven(thirdParty) }
            filter { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}

rootProject.name = "fanos"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:model")
include(":core:common")
include(":core:content")
include(":core:network")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:designsystem")
include(":core:updater")

include(":source:api")

include(":feature:library")
include(":feature:browse")
include(":feature:novel")
include(":feature:reader")
include(":feature:settings")
