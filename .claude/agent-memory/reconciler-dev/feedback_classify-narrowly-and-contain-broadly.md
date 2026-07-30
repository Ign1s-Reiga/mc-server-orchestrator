---
name: classify-narrowly-and-contain-broadly
description: Two failure-handling rules the audit drew out — the never-retry bucket must be narrow and evidence-based, and every boundary needs a catch-all so one server cannot stop the loop
metadata:
  type: feedback
---

**Make the irreversible bucket small.** A classification that means "never try
this again" has to be justified by evidence, not by the absence of success. My
`SaveOutcome.Unconfirmed` took *any* non-zero exit from `rcon-cli`, so a
connection reset became a permanent wedge with no way out. The rule the auditor
gave: never re-sending a genuinely delivered unconfirmed save is right — the bug
is routing too much into that bucket.

**Why:** an over-broad permanent classification is worse than a retry, because
the operator's only escape is an improvised `ctr task kill` on a live server.

**How to apply:** for each permanent verdict, name the observation that proves
it. "The client exited non-zero" proves nothing about the server; "the server
replied" does. And whenever a message tells an operator to do something, check
that doing it actually works — the `DRAIN_STALLED` text told them to enable RCON,
which made things strictly worse.

**Contain broadly at every boundary.** `Node` promises callers see nothing but a
`NodeException`, and the reconcile worker is built on that promise: anything else
escaping `launch` cancels the scope that owns the resync ticker, the change-feed
poller and every other worker, so one server's bad definition stops the
orchestrator reconciling all of them. Three places needed it — `LocalNode`
translating everything (not just `CriException`), the `Pass` built inside the
guard because its `require`s can reject a definition, and a `catch (Throwable)`
in the worker that rethrows `CancellationException`.

**Why:** the loop is a shared runtime. A failure that is one server's problem
must stay one server's problem.

**How to apply:** anything crossing into `:core` from a filesystem, a store or a
runtime gets translated at the edge that owns it, and the worker keeps its
last-line-of-defence catch even so. `withContext(NonCancellable)` for cleanup
that takes a lock — a cancelled coroutine throws instead of waiting, which would
leak the queue entry.

**Every `launch`ed child needs it, not just the worker.** I applied this to
`work()` and stopped there; `resyncPeriodically` and `watchChanges` went two
audits without it, and they are worse: a worker dying costs one pass, a ticker
dying cancels the scope and takes the whole loop down. Worse still, `seed`
resyncs *before* the children are launched, so the same throwable killed the
process on every restart — and an orchestrator that cannot start is the one
thing that could have repaired the state stopping it.

**The tell that a `catch` is a bet, not a guard.** `catch (StoreException)` is a
bet on what the layer below throws. The case that broke it: a NULL primary key in
SQLite raises an NPE, because the column has no `NOT NULL`, `resourceName` takes
a non-null `String`, and the JDBC wrapper only translates `SQLException`. No
`catch` on the path saw it. So at a boundary where an escape is fatal, catch
`Throwable` and rethrow `CancellationException` — the specific bug gets fixed at
source elsewhere, but the guard has to hold for the *next* unanticipated one.
