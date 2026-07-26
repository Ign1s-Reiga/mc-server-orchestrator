plugins {
    id("mcorch.kotlin-conventions")
    application
}

// :cri is deliberately absent. Every container operation goes through the Node
// abstraction in :core, so the composition root must not be able to name a CRI
// type. If wiring ever cannot construct a Node without reaching for :cri, the
// Node seam is wrong — fix the seam, do not add the dependency.
dependencies {
    implementation(project(":schema"))
    implementation(project(":core"))
    implementation(project(":store"))
    implementation(project(":api"))
    implementation(libs.kotlinx.coroutines.core)
    // API only, as everywhere else. No binding is on the classpath yet, so
    // slf4j falls back to its no-op logger and says so at startup — the loop's
    // structured logging is written and going nowhere until one is chosen.
    implementation(libs.slf4j.api)
}

application {
    mainClass = "mcorch.app.MainKt"
}

// Integration tests run against a real local containerd and are NOT wired into
// `check` — they need scripts/dev/containerd-up.sh first, so `./gradlew build`
// must stay runnable without a container runtime present.
val integrationTest =
    sourceSets.create("integrationTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }

configurations["integrationTestImplementation"]
    .extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"]
    .extendsFrom(configurations["testRuntimeOnly"])

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs integration tests against a real local containerd (see scripts/dev/containerd-up.sh)."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}
