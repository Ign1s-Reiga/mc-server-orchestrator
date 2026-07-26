---
name: localnode-test-gap
description: LocalNode cannot be unit-tested without naming a mcorch.cri type in :core tests — two safety findings landed there before the decision logic was moved out into WorkloadView and HostPaths
metadata:
  type: project
---

`:core`'s tests may not name a `mcorch.cri` type (CLAUDE.md invariant 7 is
checked by grep, and `LocalNode.kt` being the only importer is verified on every
hand-off). `CriClient` is an interface, so a fake is technically easy — the
constraint is the invariant, not the language.

Two audit findings landed in exactly the code that constraint made unreachable:
the `AlreadyExists` adoption path, and an empty
`PodSandboxStatusResponse.containers_statuses` reading as an empty sandbox and
tearing down a live server.

**The answer taken, twice:** move the *decisions* out of `LocalNode` into objects
over this module's own types — `HostPaths` (host directories, `IOException`
translation) and `WorkloadView` (which container is this server's, what state
that makes the workload, is anything still inside this sandbox) — and unit-test
those. What stays in `LocalNode` is a field copy from CRI types into
`ContainerView`, which is mechanical.

**Why:** it keeps the invariant *and* tests the logic, and it holds the line at
the same place the interface already does. A fake `CriClient` in `:core` tests
would test the mapping too, at the cost of the property the coordinator verifies
by grep every round.

**How to apply:** if a third finding lands in the mapping layer itself rather
than in a decision, that is the point to escalate for a fake `CriClient` — the
coordinator has already offered to take it up. Until then the mapping is
`integration-tester` territory, along with `AlreadyExists` adoption and whether
any real containerd populates `containers_statuses`.
