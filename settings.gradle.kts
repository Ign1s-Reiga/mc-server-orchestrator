plugins {
    // Resolves and downloads a JDK 25 toolchain on machines that do not have one.
    // This repo targets JVM 25; the build must not silently compile against
    // whatever JDK happens to be on PATH.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mc-server-orchestrator"

dependencyResolutionManagement {
    // Modules must not declare their own repositories.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(
    ":schema",
    ":cri",
    ":core",
    ":store",
    ":api",
    ":app",
)
