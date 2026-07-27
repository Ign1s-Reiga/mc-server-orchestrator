---
name: cancellation-exposure
description: What the NonCancellable save-record shield does and does not cover, the two windows the seventh drain audit ruled correctly left open, and why write-ahead was rejected on its merits
metadata:
  type: project
---

The save record is now durable against cancellation
(`Reconciler.recordIssuedSideEffect`, `NonCancellable` around the store write and
its `forceRecord` fallback). The seventh drain audit signed this off and ruled
both remaining windows correctly left open. Both stay open deliberately.

**1. Cancellation *inside* the save exec is not covered, and it is the wider
window by orders of magnitude.** The shield starts when `requestSave` returns; a
cancellation while the RCON exec is in flight can leave `save-all flush`
delivered with nothing recorded, and the next process re-sends it. The exec can
run for `spec.lifecycle.drain.saveTimeout` (minutes), the record write for
milliseconds.

**Why:** widening the shield over a container operation is ruled out — a
shutdown must not wait out a save timeout, which is the promise `Main`'s KDoc
makes. The textbook alternative, writing `saveRequestedAt` *before* issuing the
request, is worse on its own merits and not merely out of scope. Three reasons,
the first of which is the auditor's and the strongest:

- **Write-ahead opens a wider window than it closes.** The write-ahead is itself
  a suspension point and the exec dispatch is another, so a cancellation only has
  to land in the interval *between* them. The window it replaces needs a
  cancellation inside one specific in-flight RPC.
- The resulting wedge tells an operator "a world save was requested at X and its
  completion was never confirmed" about a server that got **no save at all** —
  an unrecoverable state handed to a human on a fact the code invented.
- A crash between the write-ahead and the exec trades a rare repeat for a rarer
  *undeletable server*, and it breaks the disjointness the refactor bought
  (`:572` the only writer of `worldSavedAt`, reachable only when
  `saveRequestedAt` is null).

**The ruling has an expiry.** It holds only while every side effect the drain
issues is idempotent game-side. A deregistration, a transfer or a kick voids it —
those are not safely repeatable, so a drain that issues one needs this reopened.

**2. The teardown's partial-removal record has the same shape and was left
alone.** `Reconciler.teardown` writes `containerId = null` after
`removeWorkload` reports the container gone and the sandbox not; the field is
sticky (`runtimeIdentity` never clears it from an observation), so losing that
write leaves the next pass reading `SANDBOX_ONLY` with `hadContainer = true` and
aborting for ever — an undeletable server.

**Why not fixed — and the auditor rejected my reasoning while agreeing with the
outcome.** I argued "the record write is a vanishing fraction of the window".
That is an unfalsifiable argument at review and reviewers distrust them on
principle. **What actually carries it is what is at stake**: the container is
already gone, removed after a confirmed save, so nothing playable is at risk —
what is stranded is a sandbox and a store row.

**The test worth reusing:** an undeletable *sandbox with no process in it* is an
acceptable outcome for a cancellation; an undeletable server with a **running
container** would not be, because SIGTERM is not rare and the operator has no
reason to suspect they caused it. Reach for "what is stranded, and is anything
playable in it" rather than "how likely is this".

**How to apply:** if either comes back, the answer is not a bigger
`NonCancellable`. For (1) it is either accepting the repeat or making the exec
itself resumable; for (2) it is making the *observation* self-sufficient — a
label or a drain state that says "this loop removed the container" — so no
in-flight record is load-bearing. See [[standalone-drain-decision]] for the other
open drain rulings.

**A third dependency the shield created.** It is durable only while the store
outlives the loop, which `mcorch.app.Main` arranges by joining before
`Orchestrator.close`. Break that and the record is lost with every test green.
Pinned from both ends now: `ReconcileLoopTest` asserts the loop does not finish
unwinding until an issued save is recorded, and `Orchestrator.close` reports at
error if it runs while the loop is still going. It *reports* rather than refuses
— refusing would mean declining to close a SQLite handle, and a locked database
left behind is worse than the loss being guarded against.
