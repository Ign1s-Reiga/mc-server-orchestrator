plugins {
    id("mcorch.kotlin-conventions")
}

// REST API server — the dashboard backend. The SPA lives in a separate repo and
// is out of scope here; what this module owns is the contract it consumes, which
// is written down in api/API.md.
//
// ## No HTTP framework, and why
//
// The server is `com.sun.net.httpserver` out of the JDK, with routing, JSON
// rendering, auth and SSE framing written here. Ktor or http4k would each add
// eight to twelve artifacts, and Ktor additionally a Kotlin compiler plugin for
// serialization whose version has to track the Kotlin version — for a surface
// of roughly a dozen endpoints and one event stream, on a project whose whole
// pitch is "depends on nothing but containerd". `jdk.httpserver` exports
// `com.sun.net.httpserver` unqualified, so a classpath application resolves it
// with no module flags, and JVM 25 virtual threads make its thread-per-exchange
// model the right shape for long-lived SSE connections rather than the wrong one.
//
// The cost is real and is paid in http/: a router, a body reader with a size
// cap, CORS, and a JSON writer. All of it is ordinary code with tests, and none
// of it can go stale against an upstream release.
//
// ## No JSON parser, either
//
// Nothing here parses JSON. The two request bodies that carry structure are a
// server definition — parsed by `:schema`, and YAML 1.2 is a strict superset of
// JSON, so a JSON body goes through the same parser and comes back with the same
// field paths and source locations — and secret material, which is read as raw
// bytes precisely so it is never bound into an intermediate String.
//
// snakeyaml-engine appears in test scope only, to assert on the *structure* of
// responses rather than on substrings of them.
//
// ## Still no :core edge
//
// Every mutation this API performs is a write to desired state. Creating a
// server writes a definition; deleting one tombstones it and the reconcile loop
// starts the drain (`Reconciler.drainCause` returns DELETION for a terminating
// definition); changing a spec makes the loop drain and replace. There is no
// operation here that has to call the loop, and `purge` — the one write that
// frees a name — is deliberately not exposed, because `:core` owns the guard
// that it only happens once the containers are gone.
//
// If something here ever genuinely needs the loop, add the edge and say why in
// the same change. Do not add it speculatively.
dependencies {
    api(project(":schema"))

    implementation(project(":store"))

    // The :core edge, and it is one method wide.
    //
    // api/API.md 11 anticipated this pressure from the container-logs direction
    // and ruled that adding it is "a real decision with a real justification, not
    // something to slip in". The justification is the remote console: a console
    // command reaches a running container, which needs Node, which lives there.
    //
    // What is imported is mcorch.core.console — ServerConsole, which runs one
    // already-screened command, and ConsolePolicy, which decides whether it is
    // screened. Nothing here touches the reconciler, the node registry or the
    // drain, and nothing here can stop a container.
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // Real HTTP against a real store, so the tests need both: the embedded
    // store to bind to, and the shared example definitions to POST at it.
    testImplementation(testFixtures(project(":schema")))
    // Test-only: responses are asserted on as parsed documents. YAML 1.2 reads
    // JSON, so the parser already in this repo is also the JSON reader.
    testImplementation(libs.snakeyaml.engine)
}
