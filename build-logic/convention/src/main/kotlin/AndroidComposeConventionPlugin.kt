import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
            }
            pluginManager.withPlugin("com.android.library") {
                extensions.configure<LibraryExtension> { buildFeatures.compose = true }
            }
            dependencies {
                val bom = platform(libs.lib("compose-bom"))
                add("implementation", bom)
                add("androidTestImplementation", bom)
                add("implementation", libs.lib("compose-ui"))
                add("implementation", libs.lib("compose-foundation"))
                add("implementation", libs.lib("compose-material3"))
                add("implementation", libs.lib("compose-material-icons-core"))
                add("implementation", libs.lib("compose-ui-tooling-preview"))
                add("debugImplementation", libs.lib("compose-ui-tooling"))
            }
        }
    }
}
