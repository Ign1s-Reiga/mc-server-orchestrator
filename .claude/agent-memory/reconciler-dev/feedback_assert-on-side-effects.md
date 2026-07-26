---
name: assert-on-side-effects
description: Idempotency and drain tests must assert on recorded side effects and on the reported test count, not on returned statuses — two ways this suite could go green having proved nothing
metadata:
  type: feedback
---

Two habits for tests in this repo, both of which exist because a green suite has
already lied here once.

**1. Assert on side effects, not on what a pass returned.** An idempotency test
that checks the returned status can pass while a second container was created.
Count creates, pulls, save requests, stops and store writes; a drain test's most
important assertions are the negative ones — *no stop was issued* and *the
container is still RUNNING*.

**Why:** the invariants are about what was done to the runtime. A status is a
report, and a wrong report is exactly what the test is meant to catch.

**How to apply:** the fake node is a simulator with call counters
(`core/src/test/kotlin/mcorch/core/FakeNode.kt`), so a test drives the loop the
way the loop drives containerd. Prefer adding a counter to scripting a stub.

**2. Confirm the reported test count matches the `@Test` methods written.**
JUnit Jupiter silently drops `@Test` methods whose return type is not `Unit`, so
`@Test fun t() = runBlocking { ... }` disappears at discovery with only a
warning. This invalidated 54 of `:cri`'s tests before they noticed.

**Why:** the failure mode is a suite that passes having run nothing.

**How to apply:** route suspending bodies through a helper with an explicit
`Unit` return (`coreTest` here, `runCriTest` in `:cri`), and after the suite
passes, count the `@Test` annotations and compare against the reported total.
