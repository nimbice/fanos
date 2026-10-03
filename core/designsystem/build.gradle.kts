plugins {
    alias(libs.plugins.fanos.android.library)
    alias(libs.plugins.fanos.android.compose)
}

dependencies {
    api(libs.coil.compose)
    implementation(projects.core.model)
}
