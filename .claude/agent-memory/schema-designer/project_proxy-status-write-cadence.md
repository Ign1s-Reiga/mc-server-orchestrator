---
name: proxy-status-write-cadence
description: A running proxy rewrites its status row every pass — the unchanged-status skip cannot fire there, so no argument may rest on it
metadata:
  type: project
---

`Reconciler.writeProxyStatus` has the same unchanged-status skip a `PaperServer`
row has, and **it does not fire on a running proxy.** A converging pass stamps
`control.lastContactAt`, `backends.observedAt` and `players.observedAt` all to
that pass's `now`, so the drafted status differs from the stored one on every
pass whatever the fleet is doing.

**Why:** found by the forty-fourth audit while checking the credential field, and
recorded because it predates that field — it is a property of the proxy draft,
not of anything added to it. Invariant 5 is intact: idempotence is about side
effects, and nothing game-side repeats. What is *not* available is the sentence
"an unchanged pass does not rewrite", which is exactly the sort of thing a design
argument reaches for.

**How to apply:** never support a claim about proxy status churn, store write
volume, or a resourceVersion that stops moving with the skip. It fires for
`PaperServer` rows and not for proxy ones. If a future change wants it to fire —
so that a settled fleet is genuinely quiet — the work is to stop stamping
observation timestamps that carry no new observation, which is a different change
with its own freshness consequences: [[schema-velocity-proxy-decisions]] notes
that `lastContactAt` is deliberately *not* moved by a call that went unanswered,
and the same reasoning would have to be applied to the other two stamps.
