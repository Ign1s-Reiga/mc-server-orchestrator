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
  condition branches. Same trap one level down: the permanent text asserted "still
  joinable" on the one abort reached *because* nothing could be confirmed about
  who was on the server.

## A KDoc that argues from what a consumer does is a claim to check against it

Round 19: the record-level save rule justified living on `DrainProgress` with
"`Reconciler` writes them onto one observed status side by side". It writes
`players = progress.occupancy ?: previous.players`, so on a pass whose probe did
not answer the pair on the status is a *carried-forward* count beside *this*
pass's drain. Harmless today and wrong as an argument, which is what the next
reader inherits.

The rule is genuinely over the pair a pass **established**, which is what
`DrainProgress` carries — so the placement was right and the justification was
about the wrong object. **When a doc comment reaches into another module to
explain why something sits where it does, open that module.** The same habit that
catches a wrong predicate catches a wrong rationale, and a wrong rationale is
what a later change is judged against.

## Where such a test can live, and where it cannot

`:api` has no `:core` dependency, not even for tests — so an `:api` test can only
assert against a `conditions` list somebody typed, and **would have passed against
the inverted rule**. `:core` stops at the status object. `:app` is the only module
depending on both: `app/src/test` now drives a real `Reconciler` over a real
`EmbeddedStore` with a stub `Node`, then reads it back through a real `ApiServer`
over a socket (`DisplayConformanceTest`). Writing a `Node` outside `:core` is fine
— invariant 7 is about naming `mcorch.cri` types, and a fake node is an
implementation of the seam.

**Write the joint property as an implication and apply it to every state the
suite produces**, including where it is vacuous. Mine was wrong on first contact:
"progressing or flagged" failed the players-online drain, which sits in
`DRAIN_FAILED`, is not progressing, is never flagged, and is completely correct.
`DRAIN_FAILED` is not a proxy for *stuck*. The consumer-side discriminator is
whether anybody is on the server, which the API already renders. If a property
fails on a state everyone agrees is fine, the property is wrong — fix it rather
than carving out the case.

See [[escalation-ruling]] for the specific rule and its two ordering traps, and
[[assert-on-side-effects]] for why a green `:core` suite was not evidence here.
