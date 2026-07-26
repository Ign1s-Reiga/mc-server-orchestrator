plugins {
    id("mcorch.kotlin-conventions")
}

// Desired and observed state, behind an interface.
//
// sqlite-jdbc is `implementation`, deliberately: it keeps JDBC types out of any
// signature :core or :api can see, so the storage engine cannot leak through
// the interface that way.
//
// The other half of that guarantee used to be review-only, because the
// interface and its SQLite implementation live in the same module. It is now
// enforced by the compiler instead: `mcorch.store` holds the interfaces and
// nothing else, every class under `mcorch.store.sqlite` is `internal` except
// the `EmbeddedStore` factory, and that factory returns the interface types. A
// consumer cannot name SqliteStore, so it cannot depend on one. A distributed
// backend drops in behind the same interfaces and only the call to
// EmbeddedStore.open changes.
//
// slf4j is API-only, as everywhere else in this repo: :store logs schema
// migrations and open/close, :app picks the binding. The secret store logs
// nothing at all.

dependencies {
    api(project(":schema"))

    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
}

// The store's tests round-trip the very definitions the schema module's own
// examples describe, so they read those files rather than keeping a second copy
// that would drift. A plain directory reference, not a project dependency: it
// pulls in no tasks and no cross-project model access.
sourceSets {
    test {
        resources.srcDir(file("../schema/src/test/resources"))
    }
}
