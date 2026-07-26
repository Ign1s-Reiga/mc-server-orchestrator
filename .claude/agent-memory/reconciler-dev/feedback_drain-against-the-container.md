---
name: drain-against-the-container
description: A drain must be conducted against what the running container was built with and against evidence scoped to one evacuation — the two mistakes the drain audit blocked merge over
metadata:
  type: feedback
---

Two rules the `drain-auditor` blocked a merge over, both of which I got wrong by
reading the *definition* instead of the *world in front of me*.

**1. Evidence about a container expires, and the test is the *interval*, not the
observation.** The property is not "no player has been seen since the save" but
**"the confirmation is backed by an unbroken chain of positive zero-player
observations."** I failed this twice by keying on things the loop had *seen* — a
player, a container restart — when the danger is the windows where it saw
nothing: a probe that could not answer, or a loop that was not running. A
session fits in either, and the `online=0` reading that follows is true and
worthless. So: a probe that cannot answer voids the evidence exactly as a probe
reporting players does, and a confirmation is only usable while the gap since the
last recorded observation stays under `saveEvidenceMaxGap`. The loop's own
heartbeat is the only witness that it was watching.

**Why:** a blocked drain has no attempt limit by design (`failure-modes.md`
item 7 and the standalone-drain decision), so it can legitimately sit for hours
while people play. Everything built in that time would then be protected only by
Paper's SIGTERM save inside the grace period, which is forbidden item 6.

**How to apply:** when adding any new evidence to `DrainStatus`, ask what
invalidates it, where that is noticed, and *what the loop would see if it were
simply absent for an hour*. Prefer going **back** a state to get fresh evidence
over aborting: an abort that needs a human to clear leaves a server nobody can
retire. And check that a conservative rule can still satisfy itself — "reject
every confirmation with no container start time" is safe-looking and makes the
drain save, decline to stop and save again for ever, hammering a live server.

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
same as `false`, and there is **no second source worth asking**: the definition
is the thing being edited, and anything on observed status that is *derived* from
the definition (`storage.persistent`, say) agrees with the edit within one pass.
Unknown means the safe side, full stop.

**3. Ask a source the runtime is obliged to fill.** The container-level view came
from `PodSandboxStatusResponse.containers_statuses`, which is optional and
runtime-dependent; an empty one is indistinguishable from an empty sandbox, so a
live server read as "never created" and was torn down. `ListContainers` is
mandatory — prefer a mandatory call over an optional field that saves a round
trip, and when a reading would authorise destruction, ask what a runtime that
simply declines to answer would produce.

See [[standalone-drain-decision]] for what is still open, and
[[assert-on-side-effects]] for how these are tested.
