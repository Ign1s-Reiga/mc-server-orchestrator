---
name: runblocking-loop-trap
description: The "integration stall" was never a stall — runBlocking waits for the never-ending reconcile loop it launched, so a passing test parks until the JUnit watchdog
metadata:
  type: project
---

Diagnosed 2026-07-27 on `feat/paper-server-kind`, after it had been recorded as
an open `:cri` stall with exec-session exhaustion as the leading hypothesis.
**Both were wrong**, and the shape of the wrongness is worth keeping.

**What it actually was.** `runBlocking` returns when its body finishes *and every
coroutine launched in its scope finishes too*. The integration harness launches
the reconcile loop in the test's own `runBlocking` scope, and that loop never
returns by design. So a test body that passed every assertion and fell off the
end parked `runBlocking` for ever on a coroutine that was working perfectly.
Fixed in `app/src/integrationTest/.../Fixtures.kt` by cancelling the scope's
children when the body finishes.

**Why:** a *failing* body cancels the scope on its way out, so only the passing
path wedges — which is why it looked like a failure late in the run rather than
a harness bug.

**How to apply:** when something in this repo "stops making progress", check
whether it stopped or whether only its *output* stopped, before reading anything
into the last line printed. Three cheap checks that settle it in minutes:

- A thread dump showing every `DefaultDispatcher-worker` parked in
  `CoroutineScheduler.tryPark` and no lock held anywhere means *nothing is
  stuck*. A real starvation or deadlock puts threads in application frames. An
  idle dump is evidence against a hang, not for one.
- Read the store directly rather than trusting the test's own output. Copy
  `/tmp/junit-*/data/state.db` plus its `-wal`/`-shm` and open it read-only with
  python's `sqlite3`. If `observedAt` is still advancing, the loop is alive.
- JUnit's `@Timeout` in `SAME_THREAD` mode reports `TimeoutException` on a test
  whose assertions all passed. Do not read that as "the assertion never got
  there".
- A wait-loop built on `pgrep -f "gradlew build"` never exits: `-f` matches the
  whole command line, and the monitor shell's own command line contains that
  string, so the loop finds *itself* and waits for ever. This looks exactly like
  a long build and burned several 600s timeouts on 2026-08-07. Match on the PID
  (`until ! kill -0 <pid>`) or on a `BUILD (SUCCESSFUL|FAILED)` line in the log.
  Also note `cmd | tail -n` writes nothing to the output file until the pipeline
  ends, so an empty log is not evidence of a stalled build.

See [[cri-exec-timeout-attribution]] for the real (and much smaller) `:cri`
defect the same run exposed.
