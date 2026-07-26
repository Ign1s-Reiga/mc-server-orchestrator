---
name: store-design-decisions
description: The load-bearing decisions behind the :store interface (CAS concurrency, generation vs resourceVersion, tombstone delete, own-format codec) and why each alternative was rejected
metadata:
  type: project
---

The `:store` module's shape was decided in one pass on 2026-07-26 (commit `feat(store): add the Store interface and its embedded SQLite implementation`). These are the choices whose *reasoning* is not visible from the code alone.

**Concurrency is compare-and-swap, never an interactive transaction.**
**Why:** a distributed backend (etcd, a networked DB) cannot hand a caller a transaction across a network, but every candidate backend can compare a version and swap. Exposing `transaction { }` on the interface would have made the seam a lie.
**How to apply:** if a future operation seems to need read-modify-write atomicity, express it as one call carrying the version the caller read. Do not add a transaction handle to the interface.

**`putStatus` takes `observedDefinition` — the definition version the pass acted on.**
**Why:** this is the anti-lost-update guard the task called the single most important design decision. Side effects of a stale pass cannot be undone, but the store can refuse to record an observation that would make the server *look* settled at a generation the operator already replaced.
**How to apply:** the reconcile loop should always pass it. A `DEFINITION_CHANGED` conflict means requeue and re-read, not retry blindly.

**`generation` moves only on spec change; `resourceVersion` moves on every write.**
**Why:** `:core` compares `status.observedGeneration` against `generation`. A label edit or a no-op re-apply that bumped the generation would leave reconcile permanently "behind" and break idempotency (invariant 5). A completely unchanged write does not move `resourceVersion` either, so a resync does not churn the change feed.
**How to apply:** any new definition field has to be classified as spec (bumps generation) or metadata (does not) before it is stored.

**Delete is a tombstone; `purge` is a separate call.**
**Why:** the loop needs the spec — `saveTimeout`, `stopGracePeriod` — to drain the server it was just told to remove. A removed row cannot supply that. A tombstoned name also refuses to be written again (`TERMINATING`), which blocks "recreate under live players" (drain-protocol forbidden implementation #5).
**How to apply:** `:api` calls delete, `:core` calls purge once the container is actually gone.

**On-disk format is the store's own canonical key/value document, not YAML and not a column per field.**
**Why:** storing YAML and re-reading it through `ServerDefinitionParser` would make already-accepted rows unloadable the moment `:schema` tightens a validation rule — the store would lose data because of an unrelated change. A column per field ties the disk layout to `:schema`'s Kotlin field list and is unusable by a non-SQL backend. The document is canonical (sorted keys) because `putDefinition` compares encoded specs to decide whether the generation moves.
**How to apply:** keep values in their most primitive exact form (bytes, millicores, whole nanoseconds, ISO-8601 instants). Never store a *rendered* form that rounds.

**Migrations read stored documents by key, never through the current `:schema` types.**
**Why:** a migration written today has to keep producing the same result years from now, and it cannot if it depends on today's object model.
**How to apply:** in a data migration, use `PropertyDocument.parse(...).string("some.key")`, not `StatusCodec.decode(...)`.

**Deliberately left out** (see [[store-open-questions]]): pushed-down work-finding queries, a `Flow`-based watch, leases/sharding, at-rest encryption, and any purge guard based on drain state.
