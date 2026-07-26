---
name: localnode-test-gap
description: :core tests may not name a mcorch.cri type, so LocalNode's mapping is untestable from here — three findings landed there, and the pattern that resolved the third is the one to reach for first
metadata:
  type: project
---

`:core`'s tests may not name a `mcorch.cri` type (CLAUDE.md invariant 7 is
checked by grep, and `LocalNode.kt` being the only importer is verified on every
hand-off). `CriClient` is an interface, so a fake is technically easy — the
constraint is the invariant, not the language.

Three findings have landed in exactly the code that constraint makes
unreachable:

1. the `AlreadyExists` adoption path;
2. an empty `PodSandboxStatusResponse.containers_statuses` reading as an empty
   sandbox and tearing down a live server;
3. **(2026-07-27, security)** the runtime's raw error description reaching
   `FailureStatus.message`, and so SQLite and the API. Go's
   `fmt.Errorf("...: %+v", config)` habit means a rejected `CreateContainer` can
   come back with the container's environment in it.

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

## How to apply

Reach for collapsing the decision into whichever module owns it before proposing
a fake `CriClient` in `:core` tests — the fake costs the grep property the
coordinator verifies every round, and `cri-integration-dev` agreed one test is
not worth that trade.

Where a genuine end-to-end property survives the collapse, test *that* rather
than re-testing the decision. `FailureDetailPersistenceTest` is the example: a
`NodeException` deliberately keeps the original exception as its `cause`, and
that still holds the unredacted description, so anything downstream serialising
the cause chain would leak into `state.db` with every `:cri` test passing. That
is real, it is `:core`'s to own, and it is testable from here.

See [[audit-remedies-are-hypotheses]] — the prescribed remedy is a hypothesis
even when the finding is certain, and the suite is what arbitrates.
