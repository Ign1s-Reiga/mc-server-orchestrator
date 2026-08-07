---
name: cri-stop-timeout-overflow
description: containerd inverts a large StopContainer timeout into an immediate kill and reports success; where the boundary is, and why crictl cannot be used to find it
metadata:
  type: project
---

Measured 2026-08-06 against containerd 2.3.3, the release
`scripts/dev/containerd-env.sh` pins.

**The finding.** containerd converts `StopContainerRequest.timeout` (int64
seconds) to a Go `time.Duration` (int64 nanoseconds) by multiplying by 1e9.
Above `(2^63-1)/1e9 = 9_223_372_036` that wraps, silently. A negative wrap makes
containerd skip SIGTERM and SIGKILL at once; a *positive* wrap gives a few
arbitrary seconds of grace. `StopContainerResponse` has no fields, so `{}` is
the whole answer either way and nothing downstream can tell a three-century
wait from a 300 ms kill.

The row worth remembering is `18446744083`: 584 years asked for, **9.7 s
served** — signalled, waited, killed, exactly as a healthy stop looks.

*Why it matters:* the drain protocol and CLAUDE.md invariant 3 both reason
"longer grace = safer". Past this boundary that is false.
*How to apply:* the guard is `StopGracePeriod.MAX_SECONDS`; it is a bound on
what is *expressible*, not policy. Do not move it to something round or
"sensible" — it is exactly the last value containerd honours, and
`cri/src/integrationTest` re-measures both sides of it.

**crictl cannot find this boundary.** `crictl stop -t N` derives its *own*
client context deadline from N, and that overflows first — at 9223372036 it
fails with `DeadlineExceeded` in 0.1 s having never really asked containerd.
Worse, where it overflows depends on host uptime (Go's `time.Now().Add`
includes the monotonic reading). Probe with a client whose deadline is
*independent* of the value under test: send `timeout = N` with a fixed short
deadline, then read "call outlived my deadline" as honoured and "call returned
and the container is EXITED" as inverted.

Our own client is fine at these magnitudes — verified: `CriClient.stopContainer`
with a 292-year grace period was still waiting at 12 s and cancelled cleanly, so
grpc-java's deadline arithmetic saturates rather than wrapping.

See [[cri-wrapper-design-decisions]], [[cri-guard-symmetry-rule]].
