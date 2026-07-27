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
`Fixtures.kt` contains the string `@Test` inside a KDoc block, so a raw grep
count is one higher than the real number.

**4. Never `git checkout --` a file with uncommitted work in it.** I lost a
round of edits to `DrainController.kt` reverting a deliberate one-line sabotage
that way, and the new file next to it was untracked so the same command silently
left *its* sabotage in place. Commit first, then sabotage, then restore — or copy
the file to the scratchpad.

**3. Hold the fakes to the contract, not to what the loop happens to use.**
`TestStore` ignored `deletedAt`, preconditions and kind mismatches, so the
loop's store assumptions were being validated against something more permissive
than the real store — and two of my drain tests only "passed" because of it.
`TestStoreContractTest` now pins the clauses the reconciler leans on; delete it
when `:store` publishes its `StoreConformanceSuite` as a test fixture and run
that instead. Same for `FakeNode`: it carries the labels it was created with,
because a fake that forgets them cannot catch a drain reading the wrong facts.

**The clause that is easiest to miss is cancellation.** The real store runs every
call as `withContext(dispatcher) { … }` and a real node crosses gRPC, so neither
does anything for a cancelled coroutine — that is the entire premise of the
save-record durability work. Both fakes used to do the work anyway: nothing in
`FakeNode` suspends, and `TestStore` only took an *uncontended* `Mutex`, whose
fast path never suspends and so never checks. A durability test written against
them passed against code with no shield in it at all. Both now
`currentCoroutineContext().ensureActive()` on entry. Generalise it: when a fix
turns on a property of a suspension point, check the fake actually *has* that
suspension point.
