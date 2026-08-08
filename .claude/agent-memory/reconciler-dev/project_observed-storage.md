---
name: observed-storage
description: Round 44 — why a mixed status type had to be made observed rather than renamed, the state alphabet that deliberately disagrees with forbiddenTransition, the sandbox that launders an edit through the runtime, and the volumeName half I escalated as emptied rather than frozen
metadata:
  type: project
---

`StorageStatus` is documented as observed and every field was drafted from
`definition.spec.storage` on every pass, so **a volume that did not exist
reported exactly what was asked for**. Made observed: `persistent` now comes off
`Labels.WORLD_DATA`, read from `WorkloadObservation.Present.labels`, which
`ListContainers` already returns every pass. No new query, no new RPC, no schema
version, no store migration.

**Why:** the deciding argument was that the type is **mixed**. `persistent` and
`volumeName` came from the definition; `bound` and `lastSaveConfirmedAt` were
already genuine observations. So *"rename it so nothing reads it as evidence"*
would have dragged two real observations — one of them the save-confirmation
record — into a derived bucket. A type's provenance is decided per field, and a
type that is wrong in half its fields is fixed in that half, not relabelled.

**How to apply:** this is the producer for anything that later asks *"what was
this workload built with"*. Read [[derive-from-the-consumer]] beside it: the
consumers are `StatusDrafting.worldSavedMessage`, the `VOLUME_BOUND` condition
and `:api`'s renderer, and the honest answer to "nothing observed" is the whole
block being **null** rather than a false field.

## The state alphabet, and the rule it is *supposed* to disagree with

Read the label for `CREATED`, `RUNNING`, `EXITED`, `UNKNOWN`; carry the previous
record forward for `SANDBOX_ONLY` and `Absent`. `forbiddenTransition` excludes
`CREATED`; this does not, **on purpose**, and the divergence is written at both
sites so nobody aligns them:

- that rule asks *would replacing this container discard a world* — a container
  that never started holds nothing to discard;
- this one asks *what was the container that exists built with* — its label
  answers that whether or not it has run.

[[record-lifetime]] says two rules answering one question is the smell and the
check is a hand comparison. The comparison is still the check; the outcome here
was that they are two questions. **Write the disagreement down, or the next round
"fixes" one to match the other.**

## The sandbox launders the edit, which is item 148 without an elvis

`WorkloadView.observe` reports the **sandbox's** labels when no container exists,
and `LocalNode` puts the same `WorkloadSpec.labels` on the sandbox as on the
container — so a sandbox created after a `persistent → ephemeral` edit carries
`world-data=false`. Reading it is the definition arriving through the runtime, in
the one window where the status record is the only memory of what the workload
held.

**The shape to carry past this field:** [[guard-lifetime]] taught that a fix
which stops deriving X from the wrong source grows a `?:` that derives X from the
wrong source. This is the same defect with **no elvis to grep for** — the wrong
source is reached through a legitimate-looking observation. Ask of any "read it
off the object" fix: *is there a state in which the object is not the one I mean,
and was it built from the thing I am guarding against?*

`hadContainer` cannot repair it (knowing a container once existed says nothing
about what it was built with), which is what the `SANDBOX_ONLY` arm has to argue
to satisfy `DrainWiringTest`'s classification scan — see
[[classification-scan-scope]].

## What I escalated: `volumeName` is emptied, not frozen

The scope split deferred `volumeName` to the mounts plumbing and said to carry it
forward meanwhile. Carrying forward is coherent; the consequence is not what the
split priced. **There is no producer at all**, so a server brought up under this
build carries null for ever — only rows an earlier build wrote have a name. Round
34's finding ("the volume name is the one thing nothing else in the system
remembers") therefore has nothing left to protect on new servers, and its test
could no longer assert a non-null name on a server the test itself creates; the
row that carries one is injected now.

The cheap alternative I named rather than took: a `Labels.VOLUME` written by
`PaperWorkload`, read back the way `persistent` is. It needs **no** `:cri` change
— the label channel is already plumbed — and it is the identical argument that
justified this round. Reported for a ruling; do not implement it unilaterally.

## The drop I accepted, and why it is a reporting loss and not evidence

`lastSaveConfirmedAt` is stamped onto the record the pass observed, and onto
nothing when there is none. Reachable only by *pre-label container + pre-field
row*: the drain's `contractOf` defaults a missing `WORLD_DATA` to `true`, so such
a container can still have a save confirmed. Inventing a block to hold the
timestamp would claim `persistent` from the drain's safe default — an assumption
recorded as an observation, which is the whole defect. Nothing gates on the
field; `DrainStatus.worldSavedAt` carries the same instant.

## Board

D61S and D61E both anchored at the refusal and both named
`pass.storageStatus(observation)` as the **defect**; it is the fix now, so the
claims were swept rather than renamed and re-anchored on the producer and its
absence branch. D73 is new (the `SANDBOX_ONLY` window). Red sets measured, not
predicted: six, one and one. Full board 90/90.

Drain-audit item 149 — a create-side guard against an ephemeral edit landing with
no container — is still open, and is now buildable: the record it would consult is
a memory of an observation.
