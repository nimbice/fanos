plugins {
    alias(libs.plugins.fanos.android.library)
    alias(libs.plugins.fanos.hilt)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.androidx.datastore.preferences)
}
