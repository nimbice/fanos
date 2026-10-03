plugins {
    alias(libs.plugins.fanos.android.library)
    alias(libs.plugins.fanos.hilt)
    alias(libs.plugins.fanos.room)
}

// The exported schemas, for the migration tests to build old databases from.
configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("androidTest") { assets.srcDir("schemas") }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
