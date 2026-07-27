plugins {
    id("mcorch.kotlin-conventions")

    // The example definitions under src/testFixtures/resources are shared: they
    // are this module's test data *and* the only copy :store's round-trip tests
    // read. They used to be reached by pointing :store's test resources at
    // ../schema/src/test/resources, which worked but made another module's tests
    // depend on this one's fixture layout with nothing at this end to say so.
    //
    // As test fixtures the sharing is declared instead. Consumers ask for the
    // `schema-test-fixtures` capability explicitly; a reorganisation here is a
    // change to a thing this module publishes, not an invisible break elsewhere.
    //
    // This does not widen :schema's API. The plugin adds source sets and two new
    // variants; it puts nothing on the main compile classpath, and the fixture
    // variant is only selected by `testFixtures(project(":schema"))`. A plain
    // `project(":schema")` dependency — what :core, :api and :app have — resolves
    // exactly as before.
    id("java-test-fixtures")
}

// Server-definition types (the CRD equivalent) + YAML parsing and validation.
//
// YAML library: snakeyaml-engine, used through its low-level `Compose` API
// (node tree + source marks) rather than an object binder. The reasons are in
// gradle/libs.versions.toml; the short version is that this module has to
// report every validation problem in a file at once, with a field path and a
// line number for each, and reject unknown keys. A binder (kaml, Jackson)
// throws on the first problem and hides the ones behind it.
//
// snakeyaml-engine is an implementation detail: no type it defines appears in
// this module's public API, so swapping it later touches only mcorch.schema.yaml.
//
// :schema is depended on widely. Breaking changes here must update every
// consumer in the same change.

dependencies {
    implementation(libs.snakeyaml.engine)
}
