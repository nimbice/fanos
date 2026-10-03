plugins {
    alias(libs.plugins.fanos.android.feature)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media3.session)
    implementation(libs.sherpa.onnx)
    implementation(libs.commons.compress)
    implementation(projects.core.network)
}
