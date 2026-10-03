plugins {
    alias(libs.plugins.fanos.android.library)
    alias(libs.plugins.fanos.hilt)
    alias(libs.plugins.kotlin.serialization)
}

val extensionsPublic = "https://github.com/nimbice/fanos-extensions/releases/download/extensions"

android {
    defaultConfig {
        buildConfigField("String", "UPDATES_API", "\"https://api.github.com\"")
        buildConfigField("String", "UPDATES_REPO", "\"nimbice/fanos-builds\"")
        // Public releases, read without a key: update.json and the APK beside it, under the latest release.
        buildConfigField("String", "UPDATES_PUBLIC", "\"https://github.com/nimbice/fanos/releases/latest/download\"")
        // The extensions published for everyone, read without a key: index.json and the APKs it lists, in the extensions
        // repository's release tagged "extensions".
        buildConfigField("String", "EXTENSIONS_PUBLIC", "\"$extensionsPublic\"")
    }
    buildTypes {
        // An emulator test points a debug build's updater at a stand-in: -PupdatesApi=http://10.0.2.2:8765. Never a release.
        getByName("debug") {
            buildConfigField("String", "UPDATES_API", "\"${findProperty("updatesApi") ?: "https://api.github.com"}\"")
            buildConfigField("String", "EXTENSIONS_PUBLIC", "\"${findProperty("extensionsPublic") ?: extensionsPublic}\"")
        }
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.datastore)
    implementation(projects.core.network)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
}
