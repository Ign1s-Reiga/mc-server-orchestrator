---
name: integration-freeze
description: Against real containerd the loop stops making progress about 60 seconds in, right after a node call fails — open, with the evidence gathered so far
metadata:
  type: project
---

The first integration runs (2026-07-27) found a reproducible stall that the unit
suite cannot see, and it is **not fixed**.

**Shape.** A Paper server is created and started. Its status is `STARTING` for
about fifty seconds while readiness probes correctly report "not joinable yet".
At around sixty seconds the recorded phase becomes `UNKNOWN` — which only comes
from a `NodeException`, i.e. a CRI call failed — and from that moment the process
makes no further progress: no further passes, and the *test's* own store reads
never return either.

**What is known good, by hand, at the moment of the stall:** the server is
healthy and joinable (`crictl exec ... mc-monitor status` answers
`online=0 max=20`), the volume is mounted and writable, and `crictl stop` stops
the container in three seconds. So the runtime, the image and the container are
all fine — it is the orchestrator that stops.

**Leading hypothesis:** exec session exhaustion or a leaked stream in `:cri`'s
`ExecSync`. The probe runs every two seconds, so roughly 25–30 execs succeed
before the failure, and a leak that blocks the channel afterwards would explain
both the `UNKNOWN` and the freeze. Unproven.

**Ruled out:** the loop sharing `runBlocking`'s single thread. That *was* a real
defect and is fixed (`Orchestrator.run` dispatches to `Dispatchers.Default`), but
the stall survives it.

**How to apply:** do not treat `:app:integrationTest` as green until this is
found; only the bring-up path has ever been observed working end to end, and that
was before the stall. Start with `:cri`'s exec implementation and with what the
node call at ~60s actually throws — the phase is recorded but the exception's
message is not, which is itself worth fixing. See [[localnode-test-gap]] for why
so little of this is reachable from unit tests.
