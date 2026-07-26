---
name: drain-against-the-container
description: A drain must be conducted against what the running container was built with and against evidence scoped to one evacuation — the two mistakes the drain audit blocked merge over
metadata:
  type: feedback
---

Two rules the `drain-auditor` blocked a merge over, both of which I got wrong by
reading the *definition* instead of the *world in front of me*.

**1. Evidence about a container expires; scope it to one evacuation.** A
`worldSaved` flag that is set once and never invalidated will authorise a stop on
a save confirmed a play session — or a container lifetime — earlier. Any probe
reporting players online voids it, and a confirmation older than
`observation.startedAt` is not evidence about the process now running.

**Why:** a blocked drain has no attempt limit by design (`failure-modes.md`
item 7 and the standalone-drain decision), so it can legitimately sit for hours
while people play. Everything built in that time would then be protected only by
Paper's SIGTERM save inside the grace period, which is forbidden item 6.

**How to apply:** when adding any new evidence to `DrainStatus`, ask what
invalidates it and where that is noticed. `requireEmpty` is the single place a
positive player count is observed, so it is the single place evidence can be
voided; `advance` drops confirmations older than the container. And prefer going
*back* a state to get fresh evidence over aborting: an abort that needs a human
to clear leaves a server nobody can retire.

**2. Read the container's own labels, not the edited definition.** `storage.mode`
and `network.rcon` are both in the spec hash, so editing them starts a drain of
the container created from the *old* values. Anything that asks "does this server
hold world data" or "can a save be confirmed here" during a drain must ask the
workload (`Labels.WORLD_DATA`, `Labels.SAVE_CONFIRMABLE`, surfaced through
`WorkloadObservation.Present.labels`), or the drain will skip a save or send one
into a socket nobody is listening on.

**How to apply:** the same shape works for any future recreate-level field —
record the fact on the workload at plan time, read it back through the
observation. Absent means "created before this label existed", which is not the
same as `false`.

See [[standalone-drain-decision]] for what is still open, and
[[assert-on-side-effects]] for how these are tested.
