plugins {
    alias(libs.plugins.fanos.android.library)
    alias(libs.plugins.fanos.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(projects.core.content)
    implementation(projects.core.common)
    implementation(projects.core.network)
    api(projects.core.updater)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.source.api)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.turbine)
}
