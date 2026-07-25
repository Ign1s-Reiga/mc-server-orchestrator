plugins {
    id("mcorch.kotlin-conventions")
}

// Reconcile loop, scheduler, and the node abstraction — the distribution seam.
//
// :cri is `implementation` on purpose. Every container operation goes through
// the Node abstraction, so nothing downstream of :core should be able to reach
// a CRI type directly.

dependencies {
    api(project(":schema"))

    implementation(project(":cri"))
    implementation(project(":store"))
    implementation(libs.kotlinx.coroutines.core)
}
