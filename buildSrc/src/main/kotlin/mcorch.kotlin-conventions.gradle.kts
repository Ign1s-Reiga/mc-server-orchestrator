import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
    id("com.diffplug.spotless")
}

// Precompiled script plugins do not get the generated `libs` accessor, so the
// catalog is read through its extension instead.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    // CLAUDE.md: public API gets explicit visibility and return types.
    explicitApi()

    // Pinned rather than inherited: this repo targets JVM 25 regardless of the
    // JDK running Gradle. settings.gradle.kts provisions one if it is missing.
    jvmToolchain(25)
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platformLauncher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    kotlin {
        // Generated CRI stubs are build output. They are never hand-formatted
        // and never hand-edited — see the generate-cri-stubs skill.
        target("src/**/*.kt")
        targetExclude("**/build/**")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
