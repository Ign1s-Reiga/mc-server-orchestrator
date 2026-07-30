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

**Migrations read stored documents by key, never through the current `:schema` types — and compare against frozen literals, never live constants.**
**Why:** a migration written today has to keep producing the same result years from now, and it cannot if it depends on today's object model. The subtler half was found by the sixth drain audit: V3 checked `encoding != PropertyDocument.ENCODING_VERSION`, so the day that constant is bumped V3 starts refusing exactly the rows it was written to rewrite and every store still at v2 refuses to open. Loud and lossless, but stranded — and it evades the never-edit-a-shipped-migration rule because nobody edits anything, the meaning changes underneath.
**How to apply:** in a data migration use `PropertyDocument.parse(...).string("some.key")`, not `StatusCodec.decode(...)`; and inline any constant it compares against as a literal with a comment saying the magic number is deliberate. Pin the test fixture's counterpart to a literal too, so the test keeps meaning the same thing after the live constant moves. Handling a later encoding is the job of the migration that introduces it.

**A `SecretValue` is shared and destroyed on the reconcile hot path.**
**Why:** `:core` resolves a secret, uses it, and destroys it in a `finally` (added 2026-07-26). So `use` and `destroy` overlap on different threads as a matter of course, and the type owns that — callers are not expected to coordinate.
**How to apply:** any new accessor on it has to hold the same guarantee: a caller gets whole material or an exception, never a partly wiped buffer. That is why copying and wiping share a lock rather than relying on a flag.

**A decode failure is scoped to one server, and the two halves of a row are scoped differently.**
**Why:** the ninth drain audit (2026-07-31) found `rows.mapAll(::readServer)` made the first undecodable row fail the whole list read. An *observation* that will not decode leaves a perfectly good definition, so the server stays in the list carrying `StoredServer.unreadable`; a *definition* that will not decode cannot produce a `StoredServer` at all, and fabricating one would be the coercion the audit forbade. The definition case still fails `listServers` rather than shortening it silently, because `:api`'s SSE resync derives `removed`/`PURGED` from absence — a dropped row would be reported to the operator as a deletion that never happened, which is worse than a loud failure. `listAll`/`listAllByDrainState` return those rows as `UnreadableServer` entries instead.
**How to apply:** `neverObserved` (not `status == null`) is the "nothing has been observed" test — the distinction exists so a half-finished drain is not restarted from the beginning. Anything giving absence a meaning must read `listAll`.

**Signatures on `Store` are effectively frozen by out-of-module fakes.**
**Why:** `core/src/test/.../TestStore.kt` and `api/src/test/.../StoreFailureTest.kt` both implement `Store`, so changing a method's *return type* breaks `:core` and `:api` compilation even though neither production module would need editing. That is what ruled out making `listServers` return a richer type and forced the fix to be additive: a new field with a default on `StoredServer`, plus new interface methods with default bodies.
**How to apply:** any new capability on the interface has to arrive as a defaulted member or a defaulted parameter. A default body that delegates to the strict call is honest, not a lie, when the strict call would have thrown rather than hidden the case.

**Deliberately left out** (see [[store-open-questions]]): pushed-down work-finding queries, a `Flow`-based watch, leases/sharding, at-rest encryption, and any purge guard based on drain state.
