import java.util.Properties

plugins {
    alias(libs.plugins.fanos.android.application)
    alias(libs.plugins.fanos.android.compose)
    alias(libs.plugins.fanos.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The release key, named in local.properties (`signing=<path to a properties file>` with storeFile, storePassword,
 * keyAlias and keyPassword), kept outside the repository; null where there's none, and release builds go unsigned.
 */
fun Project.signingKey(): Properties? {
    val local = rootProject.file("local.properties").takeIf { it.exists() }?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
    val path = local?.getProperty("signing") ?: return null
    return Properties().apply { file(path).inputStream().use { load(it) } }
}

android {
    namespace = "io.github.nimbice.fanos"

    defaultConfig {
        applicationId = "io.github.nimbice.fanos"
        // Builds are stamped from the command line: -PbuildVersionCode=N, -PbuildVersionName=1.0.0 (or
        // -PbuildVersionSuffix=-test.N on the base version), -PbuildAbi=arm64-v8a. A release must be stamped (see below).
        versionCode = (findProperty("buildVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("buildVersionName") as String?) ?: ("0.1.0" + ((findProperty("buildVersionSuffix") as String?) ?: ""))
        (findProperty("buildAbi") as String?)?.let { abi -> ndk { abiFilters.addAll(abi.split(',')) } }
    }

    signingKey()?.let { key ->
        signingConfigs.create("release") {
            storeFile = file(key.getProperty("storeFile"))
            storePassword = key.getProperty("storePassword")
            keyAlias = key.getProperty("keyAlias")
            keyPassword = key.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Shrunk as a release is, but signed with the debug key: installs over a debug build, for trying the shrinking
        // out on a device that has one. Never published.
        create("staging") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Native libraries compressed in the APK, unpacked when it's installed: the natural voices' engine is 24 MB as it
        // is, about half that compressed, which every update download saves.
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        // The built-in dictionary is read where it lies, a block at a time; its blocks are compressed already.
        noCompress += "dict"
    }

    sourceSets {
        // LICENSE and THIRD_PARTY_NOTICES.md, copied from the repository's root by copyLegal: Settings > About shows them.
        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/legal").get().asFile)
    }
}

// A release or staging build without a version stamp would be versionCode 1, which nothing could update to.
tasks.matching { it.name == "packageRelease" || it.name == "packageStaging" }.configureEach {
    // Read now, so the action keeps a value and not the build script (the configuration cache can't hold that).
    val stamped = providers.gradleProperty("buildVersionCode").isPresent
    doFirst {
        if (!stamped) throw GradleException("Stamp the build: -PbuildVersionCode=N -PbuildVersionName=X.Y.Z")
    }
}

val copyLegal = tasks.register<Copy>("copyLegal") {
    from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.md"))
    into(layout.buildDirectory.dir("generated/legal/legal"))
}
tasks.named("preBuild") { dependsOn(copyLegal) }

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.network)
    implementation(projects.core.designsystem)
    implementation(projects.core.updater)
    implementation(projects.feature.library)
    implementation(projects.feature.browse)
    implementation(projects.feature.novel)
    implementation(projects.feature.reader)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
