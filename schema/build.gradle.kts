plugins {
    id("mcorch.kotlin-conventions")
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
