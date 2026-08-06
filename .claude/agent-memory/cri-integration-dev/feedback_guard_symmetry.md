---
name: cri-guard-symmetry-rule
description: When two layers check the same value, the outer one must ask the type that owns the rule rather than restate a weaker version of it
metadata:
  type: feedback
---

When a value is validated in more than one place, the outer layer asks the type
that owns the constraint; it does not restate the rule, not even a "safe subset"
of it.

**Why:** `LocalNode.stopWorkload` had `gracePeriod.isPositive()` in front of a
`StopGracePeriod` that enforced strictly more. Values in the gap
(`Duration.INFINITE`) cleared the local guard and blew up inside `translating`,
where the catch-all for unclassified `RuntimeException`s produces a
**non-retryable** `NodeException.Rejected` — a permanent drain abort caused by
an argument, with the runtime never contacted. A permanent abort on a stop is
the shape that has produced several criticals in this repo's audit chain. A
second, better-informed local guard would be the same defect again.

**How to apply:**
- Prefer removing the throwing entry point entirely. `StopGracePeriod.of`
  returns `Result` now, so no caller can accidentally let an
  `IllegalArgumentException` cross the `:cri`/`:core` boundary.
- Fakes count. `FakeNode` carried the same weaker `isPositive()` and would have
  accepted what the real node refuses; it now calls `StopGracePeriod.of`.
- `:app`'s `StubNode` is the one that *cannot* be fixed this way: `:app`
  deliberately has no `:cri` on its classpath, so its check is a restatement by
  necessity. Leave it, and do not "fix" it by adding `:cri` to `:app`.
- Value construction that can reject (`ContainerId(...)`) belongs *inside* the
  error-translating block, so its failure is classified rather than escaping as
  a raw exception past a caller's `catch (NodeException)`.

See [[cri-stop-timeout-overflow]].
