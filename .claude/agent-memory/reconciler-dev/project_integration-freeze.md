---
name: integration-freeze
description: The "integration stall" was never a stall — runBlocking parented the reconcile loop, so a passing test could never return. Resolved, with the two things that identified it, plus why two concurrent integration runs on the shared dev containerd report a permanent create failure that is not yours
metadata:
  type: project
---

**Resolved 2026-07-27. The earlier version of this memory was wrong on both
counts** — there was no stall in `:cri`, and the exec-exhaustion hypothesis was
disproven. It is written out here because a plausible wrong diagnosis is worth
more than a deleted one: this one cost two full runs.

## What was actually happening

`ContainerdHarness.start(scope)` launched the reconcile loop as a child of the
test's `runBlocking`. `runBlocking` does not return until every child coroutine
finishes, and the loop never finishes by design. So a test body that passed all
its assertions and fell off the end parked for ever, and `@AfterEach` could
never run.

**Only the passing path wedged**, which is what made it read as a late-run
failure. A *failing* body cancels the scope on its way out, so a failure looked
like an orderly failure and a success looked like a hang.

## How it presented, and why that misled

The `UNKNOWN` phase about sixty seconds in was real, benign and separately
caused: a Paper server still generating its world outran the 10s probe timeout,
containerd stopped the command and reported `DEADLINE_EXCEEDED` — the same gRPC
code `:cri` produces when its *own* transport deadline elapses. A healthy
runtime was therefore recorded as `RUNTIME_UNREACHABLE`. Two unrelated problems
arriving at the same moment read as one causal chain, and the `UNKNOWN` was
taken as the trigger for the freeze rather than as a coincidence.

Nothing was logged, either — there was no slf4j binding on any classpath — so
the only evidence was a phase with no message behind it.

## The two things that identified it

Worth reusing, because both are cheap and both are decisive:

1. **An idle thread dump is evidence *against* a hang.** All four workers were
   parked with no work and no lock held, and the test thread was inside
   `BlockingCoroutine.joinBlocking`. Nothing was stuck; something was simply
   never going to return. A deadlock looks completely different.
2. **Read the store directly to tell "finished" from "blocked".** The live
   SQLite file, opened 4.5 minutes after the last console output, showed
   `observedAt` still advancing at `phase=RUNNING ready=true`. The loop was
   working perfectly the whole time the run appeared dead.

## How to apply

Do not chase a `:cri` stall; there is not one. When an integration run goes
quiet, establish *first* whether the loop is stalled or merely unable to return
— dump threads, then read the store — before forming a hypothesis about which
component broke. And when two symptoms appear together in a system this
asynchronous, check whether they are actually one thing before assuming they
are.

## The dev containerd is one shared instance, and two runs collide

Observed 2026-08-06. `:app:integrationTest` cannot run concurrently with another
agent's copy of it: the sandbox *name* is derived from the fixture, so the second
run gets `failed to reserve sandbox name "mcorch-it-bringup_…" is reserved for
<sandbox id>` and the orchestrator reports it as
`CONTAINER_CREATE_FAILED/PERMANENT` with the "something on this node holds the
name without carrying this orchestrator's labels" message. **That reads exactly
like a real regression against your own change**, and it is not — it is the
neighbouring run's live sandbox.

- Check `pgrep -f integrationTest` **before** starting one, and expect it while
  `cri-integration-dev` or `integration-tester` is working. Only one of the
  processes there is yours.
- Do not clean up sandboxes to unblock yourself: on a shared instance the orphan
  may be somebody's running fixture. Report the sandbox id and hand it over.
- A killed run leaves no JUnit XML at all, and no XML is an *unknown*, never a
  pass — the same rule the mutation harnesses apply. Two of my runs were killed
  mid-flight (one by a Gradle daemon stop) and neither said anything about the
  code.

The same contention also produced an `HttpTimeoutException` in an unrelated
`:api` test while the integration suite was loading the machine. A single
timeout-shaped failure under load is worth re-running before diagnosing.

See [[localnode-test-gap]] for why so little of this is reachable from unit
tests at all.
