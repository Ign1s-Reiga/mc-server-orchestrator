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

        // velocity-api is the one dependency in this build that Maven Central
        // does not carry, and :velocity-plugin is the one module that wants it.
        //
        // Scoped with `content { }` on purpose. An unscoped second repository is
        // consulted for *every* unresolved module, which makes resolution order
        // observable and lets a typo in any coordinate be answered by whichever
        // host happens to have something under that name. Restricted to the one
        // group, this repository can only ever serve velocity-api; everything
        // velocity-api depends on transitively (Adventure, Gson, Guava, Guice)
        // still resolves from Maven Central.
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
            content { includeGroup("com.velocitypowered") }
        }
    }
}

include(
    ":schema",
    ":cri",
    ":core",
    ":store",
    ":api",
    ":app",
    ":velocity-plugin",
)
