---
name: cri-stop-deadline-cap
description: The stop deadline is capped independently of the grace period it sends, and containerd does not escalate to SIGKILL once the request context has expired
metadata:
  type: project
---

Added 2026-08-07 (`CriTimeouts.stopDeadlineCap`, default 2h). The deadline is
`min(grace, cap) + slack`; **the whole grace period still goes on the wire.**

**Why the deadline and the grace had to be separated.** They were the same
number, so a grace period nobody bounded was a call nobody bounded. `:core`'s
`StopGraceCeiling` cannot own it: the grace period is half of a schema-validated
pair with `drain.saveTimeout`, so capping it needs a floor derived from the other
half, and above a two-hour save timeout that floor makes the ceiling inoperative.
Bounding the *deadline* costs nothing that matters, because the value sent is
untouched — the cap can only leave a container running longer, never kill it
sooner. That asymmetry is the sentence to lead with in a drain audit.

**containerd does not finish the job after the client's deadline elapses.**
`internal/cri/server/container_stop.go` waits out the grace period on a context
derived from the request's, and between that wait and the `SIGKILL` sits
`if ctx.Err() != nil { return ctx.Err() }`. So the kill is reached only when the
*inner* wait expired. Read in the release/2.3 source and measured against 2.3.3:
a 12s grace deadlined at 4s gave up at 4.04s and the container was still
`RUNNING` 17s after the stop was issued — five seconds past the grace it asked
for, no kill. A re-issued stop whose grace fitted inside the cap finished it in
1.74s.

*How to apply:* do not describe a capped stop timeout as "containerd keeps
stopping it". It keeps the signal it already delivered; it does not escalate. The
re-issue is what finishes a container that ignores `SIGTERM`, which is why the
`DEADLINE_EXCEEDED` must stay retryable.

**A re-issued stop does not re-deliver the signal.** `container_stop.go`
compare-and-swaps a per-container `IsStopSignaledWithTimeout` the first time a
stop with `timeout>0` sends one and skips it after — *"Skipping the sending of
signal terminated ... because a prior stop with timeout>0 request already sent
the signal"* in containerd's journal on 2.3.3. What a re-issue supplies is a
fresh grace period on a fresh context, and the `SIGKILL` is reached only when
*that* grace period expires. So a re-issue carrying the same over-cap grace
period can never reach the kill, however many times it is made. Two KDocs said
the opposite until 2026-08-07.

**What a stop that does reach the kill costs**: 1.73s for a 1s grace period —
1.00s of grace, the kill, the task dead 19ms later, then **0.71s** for the exit
event to reach the CRI event monitor and settle the container's status. That
last tail is invisible from the client and is why `deadlineSlack` is 30s and not
2s; a budget of "grace + a second or two" does not hold on a loaded host.

**Not a second guard on the grace period** — it bounds a different quantity
(wall-clock time the caller is parked), which is why it does not fall foul of
[[cri-guard-symmetry-rule]]. A reader will ask; the KDoc answers it.

See [[cri-stop-timeout-overflow]], [[cri-exec-timeout-attribution]],
[[cri-integration-sourceset]], [[cri-deadline-evidence]].
