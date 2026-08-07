---
name: localnode-test-gap
description: :core tests may not name a mcorch.cri type, so LocalNode's mapping is untestable from here — three findings landed there, and the pattern that resolved the third is the one to reach for first
metadata:
  type: project
---

**Corrected 2026-08-07: the constraint is on `:core`'s _main_ sources, not on its
tests.** `LocalNode.kt` is the only file under `core/src/main` that imports
`mcorch.cri`, and that is what the hand-off grep checks. Four files under
`core/src/test` name CRI types today — `FakeNode`, `AdoptAfterCollisionTest`,
`StopGraceGuardTest` and `NodeDispatchTest` — and two of them stand up a full
fake `CriClient` to drive `LocalNode` directly. Nothing about that touches the
invariant.

This entry used to say tests may not, and that a fake "costs the grep property
the coordinator verifies every round". It does not, and believing it would have
sent a whole round of `LocalNode` assertions through a redesign instead of a
seventy-line fake. **Check `core/src/main` before concluding a `LocalNode` path
is untestable from here.** What follows is still right about *where a decision
belongs*; it was only wrong about what is reachable.

Three findings have landed in exactly the code that constraint makes
unreachable:

1. the `AlreadyExists` adoption path;
2. an empty `PodSandboxStatusResponse.containers_statuses` reading as an empty
   sandbox and tearing down a live server;
3. **(2026-07-27, security)** the runtime's raw error description reaching
   `FailureStatus.message`, and so SQLite and the API. Go's
   `fmt.Errorf("...: %+v", config)` habit means a rejected `CreateContainer` can
   come back with the container's environment in it.
4. **(2026-08-06, drain-critical)** `mountsFor`'s `StorageRequest.Ephemeral`
   branch answering `emptyList()` while discarding the `mountPath` it was
   given — so the proxy's control plugin was requested by a planner, dropped
   here, and every backend behind a `:core`-created proxy was undrainable.
   Twenty-three static audit rounds and the whole unit suite passed over it;
   integration found it.

## Two ways out, and the second is much better

**Move the decision into `:core`**, over this module's own types — `HostPaths`,
`WorkloadView` — and unit-test it there. This resolved the first two. What stays
in `LocalNode` is a mechanical field copy.

**Move the decision *further into* `:cri`** so `LocalNode` has nothing left to
decide. This resolved the third, and it is the better instinct whenever the
decision is genuinely `:cri`'s to make. The intermediate shape — `:cri` exposing
a predicate (`CriOperation.requestMayCarrySecrets`) that `:core` combined with
the raw text itself — looked like reuse and was the worst of both: a one-token
security decision sitting in a module that could not test it, where inverting
the token left every `:core` test green. Replacing it with
`CriException.safeDescription`, which makes the decision once and hands back
only the safe string, **deleted** the gap instead of documenting it.

**The tell:** if a fix leaves `:core` holding both a raw value *and* a flag
about that value, the seam is in the wrong place. A caller that can only receive
the already-correct answer cannot get it wrong.

## The residual hop needs a structural pin

The fourth finding took the first way out — the derivation moved to
`HostPaths.mounts` over `:core`'s own types — and that closed the *decision*.
What it cannot close is the hop from that function to the CRI mount list, which
is still inside `LocalNode` and still unreachable from a test here. Somebody can
rebuild the old `when` beside the call and every behavioural test stays green.

So a source-shape assertion carries it: one `HostPaths.mounts(` call, one
`VolumeMount(` construction, the second built from the first, and no `when (` or
`emptyList()` between them. `WorkloadMountsTest` holds it and
`scripts/dev/control-plugin-mutations.sh` P10 is the proof it bites — it writes
the defect back at its original address, which is the only mutation in that set
no behavioural test catches.

**Generalise:** collapsing a decision out of `LocalNode` leaves a *copy* behind.
Ask what the copy still permits, and pin that by shape — see
[[invariants-need-an-enforcement-point]].

## How to apply

Reach for collapsing the decision into whichever module owns it first — not
because a fake is forbidden (it is not; see the correction above) but because a
decision that lives in `LocalNode` is a decision no `:core` unit test can pin
*by shape*, only by behaviour through a fake. A fake proves the path works
today; collapsing proves the next reader cannot rebuild the old one beside it.

Where a genuine end-to-end property survives the collapse, test *that* rather
than re-testing the decision. `FailureDetailPersistenceTest` is the example: a
`NodeException` deliberately keeps the original exception as its `cause`, and
that still holds the unredacted description, so anything downstream serialising
the cause chain would leak into `state.db` with every `:cri` test passing. That
is real, it is `:core`'s to own, and it is testable from here.

See [[audit-remedies-are-hypotheses]] — the prescribed remedy is a hypothesis
even when the finding is certain, and the suite is what arbitrates.
