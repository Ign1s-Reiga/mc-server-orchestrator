---
name: cancellation-exposure
description: What the NonCancellable save-record shield does and does not cover, the two windows left open on purpose, and why write-ahead was rejected rather than deferred
metadata:
  type: project
---

The save record is now durable against cancellation
(`Reconciler.recordIssuedSideEffect`, `NonCancellable` around the store write and
its `forceRecord` fallback). Two windows of the same shape stay open, both
deliberately.

**1. Cancellation *inside* the save exec is not covered, and it is the wider
window by orders of magnitude.** The shield starts when `requestSave` returns; a
cancellation while the RCON exec is in flight can leave `save-all flush`
delivered with nothing recorded, and the next process re-sends it. The exec can
run for `spec.lifecycle.drain.saveTimeout` (minutes), the record write for
milliseconds.

**Why:** the coordinator ruled out widening the shield over a container
operation — a shutdown must not wait out a save timeout, which is the promise
`Main`'s KDoc makes. The textbook alternative, writing `saveRequestedAt` *before*
issuing the request, is worse on its own merits and not merely out of scope: a
crash between the write-ahead and the exec wedges a server that never got a save
into `DRAIN_SAVE_TIMEOUT`, permanent, needing a human — trading a rare repeat for
a rarer *undeletable server*. It also breaks the disjointness the refactor bought
(`:572` being the only writer of `worldSavedAt`, reachable only when
`saveRequestedAt` is null).

**2. The teardown's partial-removal record has the same shape and was left
alone.** `Reconciler.teardown` writes `containerId = null` after
`removeWorkload` reports the container gone and the sandbox not; the field is
sticky (`runtimeIdentity` never clears it from an observation), so losing that
write leaves the next pass reading `SANDBOX_ONLY` with `hadContainer = true` and
aborting for ever — an undeletable server.

**Why not fixed:** the loss is fail-safe (retryable, escalates at
`drainAttentionAfter`, re-issues nothing, stops nothing), and the record write is
a vanishing fraction of the window — cancellation during `removeWorkload` itself
produces the identical stall. Shielding the write alone would buy almost none of
the exposure while adding a second `forceRecord` bypass to the drain teardown.

**How to apply:** if either comes back, the answer is not a bigger
`NonCancellable`. For (1) it is either accepting the repeat or making the exec
itself resumable; for (2) it is making the *observation* self-sufficient — a
label or a drain state that says "this loop removed the container" — so no
in-flight record is load-bearing. See [[standalone-drain-decision]] for the other
open drain rulings.
