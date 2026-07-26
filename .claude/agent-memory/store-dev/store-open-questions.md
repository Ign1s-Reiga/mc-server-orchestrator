---
name: store-open-questions
description: What :store deliberately does not do yet, and the one decision most likely to be overruled (the missing purge-time drain guard)
metadata:
  type: project
---

Things `:store` deliberately does *not* provide, so nobody assumes they exist and nobody re-litigates them without the reasoning.

**No purge-time drain guard — the one most likely to be overruled.**
`purge` refuses only if the definition was never deleted. It does *not* refuse when the stored status shows a drain still in flight, even though purging then throws away the record of which side effects were already issued and orphans a running container.
**Why:** the store never sees a container, so it cannot know a drain actually finished; and a guard keyed on `DrainState` degrades *silently* the day a new pre-stop state is added, which is the worst failure mode a safety check can have. The real signal ("the container is gone from the node") lives in `:core`.
**How to apply:** if this is raised in review, the answer is to put the check in `:core` where the container observation is, and to have `drain-auditor` own it — not to move it into the store.

**No pushed-down work-finding query.** `listServers()` returns everything and `:core` filters. Deciding what needs reconciling is reconcile policy, and policy in the store is policy in two places. `listByDrainState` is the single exception, justified by post-restart drain resumption.

**No `Flow` on the interface.** The change feed is a pull (`changesSince(cursor)`). The reconcile loop owns its own cadence, coalescing and backoff; a `Flow` would move that policy into the store.

**Observed state is not in the change feed.** `:core` is the only writer of status, so feeding it back would be a self-loop.

**No leases, no sharding, no ownership.** Multiple reconcile workers would need them; compare-and-swap is already the right primitive to build them on.

**No at-rest encryption of secrets.** Encrypting with a key stored next to the database changes nothing about who can read it. A real answer needs a key-encryption key held elsewhere, which is a deployment decision nobody has made. What exists instead: a separate database file, owner-only permissions, and an interface that hands out `SecretValue` and never a `String`.

**Change log grows to `changeLogRetention` (default 10 000) and then drops the oldest.** There is no time-based compaction and no `compact()` on the interface; `ChangeFeed.Expired` already models the consequence.

See [[store-design-decisions]] for what *was* decided and why.
