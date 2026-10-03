plugins {
    alias(libs.plugins.fanos.jvm.library)
    `maven-publish`
}

dependencies {
    api(libs.jsoup)
    api(libs.kotlinx.coroutines.core)
}

// Extensions build against this library; publishToMavenLocal makes it available to the extensions repository.
// The version's major number stays in step with Extensions.MIN_API_LEVEL, its minor with Extensions.API_LEVEL.
java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("sourceApi") {
            groupId = "io.github.nimbice.fanos"
            artifactId = "source-api"
            version = "1.8.0"
            from(components["java"])
            pom {
                name.set("Fanos source API")
                description.set("The interface Fanos's site extensions implement. Extensions load into Fanos's process, so they must be under the GPL or a licence compatible with it.")
                url.set("https://github.com/nimbice/fanos")
                licenses {
                    license {
                        name.set("GPL-3.0-or-later")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                    }
                }
            }
        }
    }
}
