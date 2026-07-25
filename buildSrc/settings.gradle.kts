// buildSrc has its own settings, so the root version catalog has to be
// imported explicitly for `libs` to work in buildSrc/build.gradle.kts.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
