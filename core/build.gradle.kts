plugins {
    id("mcorch.kotlin-conventions")
}

// Reconcile loop, scheduler, and the node abstraction — the distribution seam.
//
// :cri is `implementation` on purpose. Every container operation goes through
// the Node abstraction, so nothing downstream of :core should be able to reach
// a CRI type directly. `mcorch.core.node.LocalNode` is the only class in this
// module that names one, and `LocalNode.open` takes a plain endpoint string so
// that :app can wire a Node without depending on :cri either.
//
// :store is `api`, deliberately: `Reconciler` and `ReconcileLoop` take a
// `Store`, so it genuinely is part of this module's API. It is one of the three
// seams and already an interface with no storage engine in its signatures —
// re-wrapping it here would be a second abstraction over the same thing.
//
// slf4j is API-only, as everywhere else in this repo: :core logs, :app picks
// the binding.

// :velocity-plugin is `implementation`, and only for `mcorch.velocity.control`.
// CLAUDE.md blesses exactly this one arrow so the wire contract — the protocol
// version, the paths, the error codes — has one definition rather than a copy in
// the reconciler that goes stale silently. That package names no Velocity type,
// and the dependency is on this module's *thin* jar (`pluginJar` is a separate
// artifact), so nothing downstream gets a second kotlin-stdlib.
//
// `implementation` rather than `api`: no signature in :core's public API mentions
// a `mcorch.velocity.control` type, and it must stay that way — :api and :app
// have no business speaking the control protocol.

dependencies {
    api(project(":schema"))
    api(project(":store"))

    implementation(project(":cri"))
    implementation(project(":velocity-plugin"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}

// One decision written in two places, and this is what stops them drifting.
//
// `VelocityWorkloadPlanner.VELOCITY_BUILD` pins the Velocity the *proxy container*
// downloads; `libs.versions.velocity` pins the velocity-api `:velocity-plugin`
// compiles the mounted JAR against. A plugin compiled against one API line and
// loaded by a proxy on another does not load, and the proxy starts perfectly
// anyway — so the failure is a fleet with no control endpoint, discovered when
// somebody tries to drain something.
//
// Neither module can read the other's value at runtime (a compileOnly coordinate
// is not on any classpath), so the build hands the catalog's version to the test
// that asserts on the constant. A bump to one that forgets the other fails
// `:core:test` rather than an integration run against a real proxy.
tasks.test {
    systemProperty("mcorch.velocityApiVersion", libs.versions.velocity.get())
}
