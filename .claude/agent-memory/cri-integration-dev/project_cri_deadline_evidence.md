---
name: cri-deadline-evidence
description: How to read and design a :cri deadline experiment — grpc's "deadline exceeded after Xs" is not the elapsed time, and a suite that shrinks a deadline must not judge its control steps by it
metadata:
  type: project
---

Learned 2026-08-07 diagnosing a `StopDeadlineCapIT` failure that was reported,
plausibly, as a defect in `attributeCappedStop`. It was not one.

**grpc's `deadline exceeded after 2.999870100s` is not how long the call took.**
It is `deadline.timeRemaining()` captured when the call's `CancellationHandler`
was constructed, in `ClientCallImpl.startInternal` — i.e. *the deadline minus
whatever elapsed between `withDeadlineAfter` and the call starting*. A 3s
deadline reporting `2.9998s` means 130µs of setup, not a timer that fired
130µs early. Read as elapsed it looks exactly like a clock that undershoots,
and that reading turned into a diagnosis of the wrong function.
`io.grpc.Deadline$SystemTicker.nanoTime()` is `System.nanoTime()` — verified by
disassembling the grpc-api jar the build resolves — so there is no cross-clock
hazard in comparing an elapsed nanoTime against a deadline.

**A suite that shrinks a deadline to make it observable must not judge its
other steps by it.** `StopDeadlineCapIT` set `deadlineSlack` to 2s so the
capped stop it measures gives up inside the grace period; the re-issued stop at
the end — the control, which has to *succeed* — inherited the same 2s and had
that for everything that is not its grace period. It failed on a stop
containerd had completed successfully in 1.73s. `RuntimeHarness.clientWith`
exists so a control step runs on `CriTimeouts()`.
*How to apply:* whenever a `:cri` IT overrides a timeout, ask which calls in
the suite are the measurement and which merely have to work, and give the
second group the shipped values. A starved control fails in the shape of the
defect the experiment is hunting, which is the worst possible disguise.

**Reading which step of a multi-step IT threw**, before believing any
attribution of it: the JUnit XML `type=` says whether an assertion failed or a
production exception escaped, `system-out` shows how far the `println`s got,
and the top stack frame says where the exception was *constructed* — an
attributed failure is built in `attribute*` and an unattributed one in
`translateStatus`. All three said "a later step, not the assertion" here.
containerd's own journal (`journalctl -u mcorch-dev-containerd`) timestamps
every `StopContainer`, signal, kill and exit event, and is what settles it.

Greens are weak evidence for a load-dependent failure — this one passed three
times before failing twice. Argue from the margin instead, and measure it under
load: eight spinners on four cores put the re-issued stop at 6.28s, twice the
budget it used to have.

See [[cri-stop-deadline-cap]], [[cri-integration-sourceset]],
[[cri-exec-timeout-attribution]].
