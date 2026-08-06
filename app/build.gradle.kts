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
    // API to compile against, as everywhere else.
    implementation(libs.slf4j.api)
    // The binding, and this is the only module in the repo allowed to declare
    // one. `runtimeOnly` so that no code here can accidentally compile against
    // an implementation type. Levels are in
    // src/main/resources/simplelogger.properties — read the note there before
    // raising any of them, because one of them keeps an RCON password out of
    // the log.
    //
    // Inherited by the integrationTest source set through the standard
    // implementation -> testImplementation -> integrationTestImplementation
    // chain, so an integration run gets the same output a real run does.
    runtimeOnly(libs.slf4j.simple)

    // Reads the API's JSON in `DisplayConformanceTest`. JSON is a YAML subset
    // and this parser is already in the build, so it beats hand-matching
    // substrings in a response body — an assertion that passes because the key
    // moved is exactly the kind this test exists to stop.
    testImplementation(libs.snakeyaml.engine)
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

// The proxy's control plugin, as a *file* this build produces rather than a path
// anybody writes down. `:core` asks a node for `VELOCITY_CONTROL_PLUGIN` and the
// node answers out of its configured asset directory, so the integration harness
// has to stage a real artefact there — and the only honest source for one is the
// task that builds it. Declared as a resolvable configuration rather than
// `project(":velocity-plugin").tasks…` so the wiring survives configuration-cache
// and does not reach across project boundaries at execution time.
val controlPlugin: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    controlPlugin(project(mapOf("path" to ":velocity-plugin", "configuration" to "controlPlugin")))
}

// The integration source sets are compiled by `check`, and never run by it.
//
// `integrationTest` is a separate source set, so an ordinary `./gradlew build`
// did not compile it — a type change in `:core` could break these suites and
// every green build would keep saying so until somebody ran containerd. That
// happened: `EndpointRequest.timeout` became a value class and this module's
// suite stopped compiling, invisibly, for a whole round.
//
// Compiling is free and needs no runtime; running still needs
// scripts/dev/containerd-up.sh and stays opt-in.
tasks.named("check") { dependsOn(tasks.named("compileIntegrationTestKotlin")) }

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs integration tests against a real local containerd (see scripts/dev/containerd-up.sh)."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))

    // Where the harness stages it from. A proxy brought up without it comes up
    // perfectly well and has no control endpoint, so the suite refuses to run
    // rather than reporting green on a fixture it quietly skipped.
    val jar = controlPlugin.elements.map { it.single().asFile.absolutePath }
    inputs.files(controlPlugin)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-Dmcorch.plugin.jar=${jar.get()}") },
    )
    // A binding is on this classpath now, but Gradle swallows a test's output
    // unless asked. These runs take ten minutes against a real runtime and the
    // whole reason for the binding is to be able to see what the loop did while
    // they ran, so the output is shown by default rather than behind --info.
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
