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

    // The store's tests round-trip the very definitions :schema's examples
    // describe, so they read that single copy rather than a second one that
    // would drift. This used to be `resources.srcDir("../schema/src/test/...")`:
    // sound, but it made these tests depend on another module's fixture layout
    // through the filesystem, with no signal at the :schema end and an empty
    // resource set rather than a failure if the layout moved.
    //
    // Test scope only. :store's own consumers see nothing of this.
    testImplementation(testFixtures(project(":schema")))
}
