---
name: classification-scan-scope
description: What the SANDBOX_ONLY classification scan covers after round 36 — two syntaxes, five comparison sites and why each is exempt, the limitation left open on purpose, and the review rule an auditor asked for and I declined to invent
metadata:
  type: project
---

`DrainWiringTest`'s classification scan covers **two** syntaxes as of round 36: a
`when` arm whose pattern names a `WorkloadState` (continuation lines folded in), and
an `==`/`!=` against `SANDBOX_ONLY` (qualified or bare). A third test refuses a bare
entry name in any arm, which is what keeps the alphabet qualified.

**Why:** the docstring said "every classification" while the scan read arms alone,
and `:core/main` already held five comparisons — one of them a drain decision. A
claim a later round relies on has to be the claim that is enforced.

**How to apply:**

- **The limitation is deliberate and written at `Source.stateComparisons`.** A
  comparison against a *different* state — `state != WorkloadState.RUNNING` — lumps
  the two sandboxes in with `EXITED` **without naming either**, and nothing sees it.
  The `when` form of that mistake is caught (an arm must enumerate, and `else` is
  refused); the comparison form has no such obligation. If a round finds a defect
  there, this is the known gap and the fix is to widen the comparison alphabet to
  every state, at the cost of a note on every `state == UNKNOWN` in the module.
- **The five exempt comparison sites, and their reasons.** Two decide an image round
  trip (`ensureImage`, `ensureProxyImage`) — both worlds answer "ask the image
  service again", and nothing there can stop or converge. Two are `LocalNode`'s
  adoption, where the loop's `hadContainer` does not exist at all: the node re-read
  the sandbox in the same call, and both answers build rather than end a container.
  The fifth is `DrainController`'s `SANDBOX_ONLY` abort, which is a **genuine drain
  decision** and argues from routing: `containerIsDown(hadContainer)` above it
  returns for the world this loop emptied itself. Its premises are pinned (one call,
  this function's own parameter, bound, returned on) — see [[level-triggered-seal]]
  for the same pattern applied to a seal.
- **The alphabet is read from the enum declaration**, so a sixth `WorkloadState`
  enters the scan on the day it is written rather than the day somebody remembers
  the test.

## The review rule I was asked to weigh and did not invent

The auditor's read: rounds 30, 31, 32 and 36 each produced *"a residual accepted in
prose, priced against the wrong sibling"*, and proposed as a candidate check —
**for every optional field added to a persisted status type, the sentence about rows
that predate it has to state the direction of the error.**

I did not build it, and the reason is where it would have to live. In `:core` there
is nothing to key it on: the loop reads a decoded status and cannot tell a field that
was absent from one that was null. The checkable half belongs at the **decode**, in
`:store` — one legacy-row fixture per optional field, asserting what the loop then
does — which is exactly where `store-dev` was fixing `stopDispatchedAt` on
2026-08-06. A scan over KDoc prose for the word "direction" would be decoration.

Raise it as a review rule rather than an instrument unless somebody finds a way to
enumerate the optional fields of a persisted type and demand a decode fixture per
field. That version is real, and it is `:store`'s to write.
