plugins {
    id("mcorch.kotlin-conventions")
}

// REST/gRPC API server — the dashboard backend. The SPA lives in a separate
// repo and is out of scope here.
//
// No HTTP framework is pinned yet (Ktor / http4k / vanilla gRPC); that is an
// api-dev decision. Add it here when the first endpoint lands.

// No :core edge yet. The API writes desired state to :store and reads observed
// state back; :core is what watches that state and acts on it. Add the edge if
// and when something here genuinely needs to call into the loop (triggering a
// drain, say) — that is an api-dev call, not a scaffolding one.
dependencies {
    api(project(":schema"))

    implementation(project(":store"))
    implementation(libs.kotlinx.coroutines.core)
}
