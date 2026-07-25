plugins {
    id("mcorch.kotlin-conventions")
}

// Server-definition types (the CRD equivalent) + YAML parsing and validation.
//
// No YAML library is pinned yet — picking one (kaml / Jackson / snakeyaml) is a
// schema-designer decision, not a scaffolding one. Add it here when the first
// server kind lands.
//
// :schema is depended on widely. Breaking changes here must update every
// consumer in the same change.
