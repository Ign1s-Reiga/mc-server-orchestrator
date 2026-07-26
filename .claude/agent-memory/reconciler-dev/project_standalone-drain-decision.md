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

**How to apply:** when the Velocity/proxy kind arrives, those three states get
real bodies — the state machine already traverses them as recorded no-ops, so it
is a fill-in, not a reshape. Until then, do not add any "drain deadline" or
"kick after N minutes" policy, however reasonable it sounds.

## Follow-on calls I made that a human may still overrule

These were mine, not the human's, and were flagged at hand-off:

1. **Save confirmation needs RCON.** A completed save can only be *confirmed*
   through a reply, and only RCON replies. So a server with persistent storage
   and `rcon: disabled` cannot be drained at all — it records a permanent
   `DRAIN_STALLED` and keeps running. Safe, but it means a default definition
   cannot be deleted until RCON is enabled.
2. **A save requested but never confirmed is a permanent abort.** It is not
   re-sent; a human must confirm the world state. A generation bump (the
   operator editing the definition) restarts the drain and does allow a fresh
   save request.
3. **No automatic restart of a crashed or unready container.** `CONTAINER_EXITED`
   is surfaced as permanent; `READINESS_TIMEOUT` stays retryable and keeps
   probing. The schema has no restart policy, and auto-recreating is a stop path.

See [[unverified-paper-image-contract]] for what still has to be checked against
a real image.
