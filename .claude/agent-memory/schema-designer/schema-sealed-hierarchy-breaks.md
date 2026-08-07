---
name: schema-sealed-hierarchy-breaks
description: Adding a ServerKind/ServerSpec/ServerStatus variant is source-breaking in :store, :core and :api — the exact sites, and the placeholder convention used when the kind lands schema-first
metadata:
  type: project
---

`ServerKind`, `ServerSpec`, `ServerDefinition` and `ServerStatus` are sealed/enum, and several modules
`when` over them exhaustively **on purpose** — the compiler is the mechanism that stops a kind
shipping half-taught. Adding a variant therefore fails the build in four modules, and CLAUDE.md
requires `:schema` breaking changes to update every consumer in the same change.

**Why:** discovered the hard way adding `VelocityProxy` on 2026-08-04. It is not obvious from
`:schema` how far the blast radius reaches, and the sites are spread across modules other agents own.

**How to apply:** when adding a kind, expect exactly these six sites (as of 2026-08-04 — verify with
`./gradlew compileKotlin`, they move):

- `store/codec/DefinitionCodec.kt` — `encodeSpec` (`when (spec)`) and `decode` (`when (kind)`)
- `store/codec/StatusCodec.kt` — `encode`, `decode`, `drainStateOf`
- `store/test/InMemoryStore.kt` — `drainStateOf`
- `core/Reconciler.kt` — `reconcile`'s `when (definition)`
- `api/render/ServerJson.kt` — `definition(...)` and `status(...)`

When the schema lands ahead of the reconciler and store (the normal order — the schema is what those
agents configure against), the placeholder convention is:

- `:store` and `:api` raise `StoreException.Unsupported` via a named helper (`notYetPersisted`,
  `notYetRendered`), not `Failed` and not a bare throw. It is the same shape of answer as an on-disk
  layout this build does not understand — non-retryable, surfaced, fixed by a build that knows the
  kind — and `:api` already routes `StoreException` to a response rather than a 500.
- `:core` returns `ReconcileOutcome.Settled`, **not** a failure. A pass that does nothing is trivially
  idempotent and creates no container; an outcome that looked like a failure would put the server into
  backoff for something that is not the operator's doing.
- Because `:store` refuses the kind, the `:api` branches are unreachable rather than merely
  unimplemented — worth saying in the KDoc so nobody "fixes" them by writing a partial renderer.
  `ServerJson` guarantees *total* rendering, which is what makes "no player identity, no secret
  material" checkable; a half-rendered kind would quietly break that.

Also note: `:store`'s SQLite `kind` column is plain `TEXT` with no `CHECK`, so a new enum value needs
no on-disk migration and rows already stored keep decoding through the branch they always did.
