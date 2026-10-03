plugins {
    alias(libs.plugins.fanos.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.jsoup)
    api(libs.kotlinx.serialization.json)
}
