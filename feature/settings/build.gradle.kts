plugins {
    alias(libs.plugins.fanos.android.feature)
}

dependencies {
    implementation(projects.core.updater)
    implementation(projects.core.network)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}
