plugins {
    // No version: buildSrc already puts Spotless on the root build script
    // classpath, and re-requesting it with a version is a resolution conflict.
    id("com.diffplug.spotless")
}

// Root build file.
//
// Shared module configuration lives in the `mcorch.kotlin-conventions`
// precompiled script plugin under buildSrc/, not in a `subprojects { }` block
// here — cross-project configuration defeats configuration cache and project
// isolation, and this build is meant to stay parallel-friendly.
//
// Spotless is applied here as well as in the convention plugin because the
// convention plugin only reaches the six modules. Without this, the root and
// buildSrc build scripts would never be formatted or checked.
//
// Module graph (see CLAUDE.md) — `./gradlew projects` prints it live:
//
//   :schema   server-definition types + YAML parsing/validation
//   :cri      CRI client (generated stubs + idiomatic wrapper)
//   :core     reconcile loop, scheduler, node abstraction
//   :store    state persistence behind an interface
//   :api      REST/gRPC API server (dashboard backend)
//   :app      wires everything into one runnable application

spotless {
    kotlinGradle {
        target("*.gradle.kts", "buildSrc/**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint()
    }
}
