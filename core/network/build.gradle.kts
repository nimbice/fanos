plugins {
    alias(libs.plugins.fanos.android.library)
    alias(libs.plugins.fanos.hilt)
}

dependencies {
    api(projects.source.api)
    api(libs.okhttp)
    implementation(projects.core.common)
    implementation(libs.androidx.webkit)
    testImplementation(libs.okhttp.mockwebserver)
}
