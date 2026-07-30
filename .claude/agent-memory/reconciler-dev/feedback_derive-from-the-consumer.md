---
name: derive-from-the-consumer
description: A status field's correctness is decided by what reads it, not by what writes it — two of my defects here passed every unit test and were caught only by a real dashboard
metadata:
  type: feedback
---

When adding or gating a field on observed status, work backwards from the thing
that will read it and ask what a wrong answer makes a human *do*. Both bugs I
have shipped in the escalation flag were invisible from inside `:core` and
obvious from the dashboard.

**Why:** `:api` derives a `display.state` badge and ranks `TERMINATING` above
everything, deliberately — a server showing `READY` while its name is reclaimed
is the one unacceptable answer. But that means a drain that has **permanently
failed**, and whose server is therefore *still running and still joinable with
players on it*, renders as "on its way out, nothing to do". The flag exists
precisely so `display.state` does not have to lie, and it was the flag that was
wrong. Every `:core` test passed; the defect needed a dashboard pointed at a real
orchestrator and a real Paper server to surface.

**How to apply:**

- Name the consumer before writing the predicate. "Who reads this, and what will
  they do if it is false when it should be true?" A condition whose false
  negative means *an operator waits while a server sits stuck* is not the same
  risk as one whose false positive means a spurious alert — and the safe side
  differs.
- Ask whether the field can still be *written* when it is needed. My time-based
  escalation could never fire for a permanently failed replacement drain, because
  the permanent-failure gate returns before writing a status. A predicate is
  worthless if the code path that evaluates it is unreachable in the state it
  describes.
- Prose is part of the contract. A message that says "the loop keeps retrying"
  attached to a drain that has stopped is a functional defect, not a wording nit:
  it tells the reader to wait when they must act. Branch the message wherever the
  condition branches.

See [[escalation-ruling]] for the specific rule and its two ordering traps, and
[[assert-on-side-effects]] for why a green `:core` suite was not evidence here.
