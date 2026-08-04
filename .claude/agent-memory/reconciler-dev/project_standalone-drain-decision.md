---
name: standalone-drain-decision
description: The human's ruling that a standalone Paper server has no proxy, so a drain with players online aborts rather than transferring — plus the follow-on calls I made that are still open to being overruled
metadata:
  type: project
---

A standalone Paper server has **no proxy**, so drain-protocol steps 2 (stop new
joins), 4 (transfer players) and 6 (deregister backend) have no counterparty.
The human decided this before implementation and it is not to be re-derived:
with players online there is **no drain destination**, so the drain aborts with
`DRAIN_NO_DESTINATION` and requeues. Never force-stop, never kick.

**Why:** kicking players to reach zero looks lossless but their latest activity
goes unsaved (`failure-modes.md` item 4). There is nowhere to send them until a
Velocity proxy kind exists.

**How to apply:** still true for a server nothing claims. Do not add any "drain
deadline" or "kick after N minutes" policy, however reasonable it sounds.

**The "it is a fill-in, not a reshape" claim in this memory was wrong, and the
audit said so before any code existed.** Filling in steps 2–4 required moving the
zero-player gate: `requireEmpty` wrapped `SEALED`, `TARGET_RESOLVED` and
`TRANSFERRING`, and with bodies it aborts *precisely on the precondition that is
supposed to trigger the work* — a destination search that refuses to run while
players are online never runs. The gate now covers `SAVING` onward only; steps 3
and 4 have the preconditions of the thing they do. See
[[level-triggered-seal]].

## Follow-on calls I made that a human may still overrule

These were mine, not the human's, and were flagged at hand-off:

1. **Save confirmation needs RCON.** A completed save can only be *confirmed*
   through a reply, and only RCON replies. So a server with persistent storage
   and `rcon: disabled` cannot be drained at all — it records a permanent
   `DRAIN_STALLED` and keeps running.
   **Enabling RCON does not fix it**, and the audit was right that saying so was
   a trap: the setting only reaches the *next* container, and this one cannot be
   replaced without the drain that needs it. The documented way out is now a
   human saving and stopping the server themselves, after which the loop
   observes a stopped container and finishes the teardown. A log-tail
   confirmation channel (`mc-send-to-console save-all flush`, then read the
   server's own completion line out of the log) would remove the deadlock
   entirely — it needs `:schema`, `integration-tester` and `drain-auditor`, and
   nobody has done it.
2. **A save requested but never confirmed is a permanent abort.** Still true and
   the auditor endorsed it — but only for a request that *reached the server*.
   Far too much used to be routed into that bucket. A generation bump does
   **not** lift it (the drain record survives the edit); reverting the
   definition so the spec hash matches again does, because a settled pass clears
   the drain record.
3. **No automatic restart of a crashed or unready container.** `CONTAINER_EXITED`
   is surfaced as permanent; `READINESS_TIMEOUT` stays retryable and keeps
   probing. The schema has no restart policy, and auto-recreating is a stop path.
4. **A permanent failure no longer gates a terminating definition.** A delete
   that is outstanding lifts the gate for every pass, not just until the drain
   starts. Without it, a permanently stalled drain never observes anything
   again, so the loop could never notice the operator had stopped the server by
   hand — the advice on its own status was unreachable. Costs one observation
   per resync and issues nothing.

See [[unverified-paper-image-contract]] for what still has to be checked against
a real image.
