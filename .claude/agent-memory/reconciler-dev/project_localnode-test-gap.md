---
name: localnode-test-gap
description: LocalNode cannot be unit-tested without naming a mcorch.cri type in :core tests — three findings have now landed there, and the third is the escalation trigger this memory set
metadata:
  type: project
---

`:core`'s tests may not name a `mcorch.cri` type (CLAUDE.md invariant 7 is
checked by grep, and `LocalNode.kt` being the only importer is verified on every
hand-off). `CriClient` is an interface, so a fake is technically easy — the
constraint is the invariant, not the language.

**Three findings have now landed in exactly the code that constraint makes
unreachable:**

1. the `AlreadyExists` adoption path;
2. an empty `PodSandboxStatusResponse.containers_statuses` reading as an empty
   sandbox and tearing down a live server;
3. **(2026-07-27, security)** the runtime's raw error description reaching
   `FailureStatus.message`, and so SQLite and the API. Go's
   `fmt.Errorf("...: %+v", config)` habit means a rejected `CreateContainer` can
   come back with the container's environment in it.

**The answer taken, three times:** move the *decisions* out of `LocalNode` into
objects over this module's own types — `HostPaths`, `WorkloadView`, and now
`runtimeDetail` — and unit-test those. What stays in `LocalNode` is mechanical:
a field copy, or a single token passed through.

**Why:** it keeps the invariant *and* tests the logic, and it holds the line at
the same place the interface already does.

## The third one is the trigger this memory set, and it is different in kind

The first two were *decisions* that could be moved out whole. This one cannot:
the decision is `CriOperation.requestMayCarrySecrets`, which is `:cri`'s list by
design and must not be forked (a security list that drifts between two modules
is worse than no list). So `runtimeDetail` takes the answer as a plain boolean,
and the surviving untested link is the one token in `LocalNode.describe` that
supplies it. No `:core` test can cover it without naming `CriOperation`.

That is a genuine security-relevant gap, not merely an aesthetic one: if the
pass-through were inverted or dropped, every `:core` test still passes.

**How to apply:** escalate on the next round. Two shapes are worth proposing,
and the second is better — ask the coordinator to route it to
`cri-integration-dev`:

- a fake `CriClient` in `:core` tests, which costs the grep property the
  coordinator verifies every round; or
- `:cri` exposing an already-redacted accessor (`CriException.persistableMessage`
  alongside its existing log-side `loggableDetail`), so `describe()` becomes a
  plain delegation with no decision left in `:core` at all, and the whole thing
  is tested where the list lives. See [[audit-remedies-are-hypotheses]] for why
  the exact shape should still be checked against the suite rather than assumed.

Until then the mapping stays `integration-tester` territory.
