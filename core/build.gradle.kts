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

dependencies {
    api(project(":schema"))
    api(project(":store"))

    implementation(project(":cri"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}
