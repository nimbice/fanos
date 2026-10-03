import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal object ProjectConfig {
    // Android 17 (API 37.2): current AndroidX and OkHttp releases need it to compile against.
    const val COMPILE_SDK = 37
    const val COMPILE_SDK_MINOR = 2
    const val MIN_SDK = 26
    const val TARGET_SDK = 36

    /** Kotlin packages and Android namespaces start here, whatever the app ends up being called. */
    const val NAMESPACE_ROOT = "io.github.nimbice.fanos"
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

/** A module's namespace follows its path: `:core:data` becomes `io.github.nimbice.fanos.core.data`. */
internal fun Project.defaultNamespace(): String = ProjectConfig.NAMESPACE_ROOT + path.replace(':', '.').replace('-', '_')

internal fun ApplicationExtension.configureAndroid() {
    compileSdk {
        version = release(ProjectConfig.COMPILE_SDK) { minorApiLevel = ProjectConfig.COMPILE_SDK_MINOR }
    }
    defaultConfig {
        minSdk = ProjectConfig.MIN_SDK
        targetSdk = ProjectConfig.TARGET_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

internal fun LibraryExtension.configureAndroid() {
    compileSdk {
        version = release(ProjectConfig.COMPILE_SDK) { minorApiLevel = ProjectConfig.COMPILE_SDK_MINOR }
    }
    defaultConfig {
        minSdk = ProjectConfig.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
