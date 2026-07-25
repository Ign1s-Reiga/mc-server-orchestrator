plugins {
    `kotlin-dsl`
}

dependencies {
    // Putting the plugins on the buildSrc classpath is what lets the
    // convention plugin apply them by id without repeating a version.
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.spotless.gradlePlugin)
}
