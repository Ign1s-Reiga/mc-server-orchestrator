---
name: replacement-exit
description: A spec-hash input no operator can edit is a replacement trigger with no exit — why that closes a fleet's login path, which lever was chosen for the Velocity pin and why, the instance deliberately left without one, and the unbounded wait that is still open
metadata:
  type: project
---

A replacement drains the container it replaces. For a **proxy** that means
sealing its own login path and waiting for the last player to log off, because a
fleet has one front door and there is nowhere to send anybody — and that wait is
unbounded by design. So every spec-hash input is also a question about recovery:

> **Can an operator put this value back?** For `image`, `maxPlayers`, `memory` —
> yes, edit the definition and the hash matches again, the drain ends, the login
> path reopens on the next converge pass. For a value that lives in orchestrator
> source, no. The replacement fires on every proxy at once on the first pass
> after a deploy, existing players keep playing, **nobody can join**, and the
> only exits are editing our source or `crictl stop`.

Since round 33 the revert is honoured **one step later once a container stop has
gone out**: the drain finishes what it signalled and the reverted definition comes
back as the replacement, rather than the workload converging over the top of its
own stop. Same outcome for the operator, different mechanism, and the difference
is a player's session — see [[record-lifetime]].

Round 25 found this in `velocity.build`. Blast radius was dev fleets only (the
kind had never shipped), and the mechanism was live for every future bump.

## The lever, and why it is not a definition field

The audit offered "version the hash input so pre-field containers match" or
"make it an operator-settable field". The first exempts a population that barely
exists and leaves the mechanism live; the second is the one that closes it.

**"Operator-settable" has layers, and the right one is decided by the value's
lifetime.** The Velocity build tracks `:velocity-plugin`'s compile target, which
is a property of the *orchestrator build*, not of one proxy — so it became
`ReconcilerConfig.velocityBuild` plus `MCORCH_VELOCITY_BUILD` in `:app`, not a
field in the proxy YAML. That kept `:schema` and `:store` out of the change
entirely, and a per-proxy field would have invited a pin the bundled plugin
cannot load. The cost: reverting needs an orchestrator restart rather than a
definition edit, and it bumps no generation, so it does **not** lift a permanent
failure.

Two properties the fix depends on, both pinned by tests:

- the environment variable the container is given and the `velocity.build` hash
  entry come from **one** resolution (`pinnedBuild`). Split them and a proxy is
  created running one build and recorded as running another — a hash that never
  matches, so it is drained and recreated on every pass, for ever;
- an unset pin produces the canonical form **byte for byte**, or the lever would
  be introduced by way of the outage it prevents.

## What is deliberately left without a lever

`plugin.protocol` is the same shape: a constant in the hash, and bumping it
seals every proxy the same way. It gets no lever on purpose — it names what the
mounted JAR *speaks*, so pinning it would be asserting something about an
artefact rather than choosing a version, and the honest repair is the recreate.
Named rather than papered over.

## The pre-flight is asked twice, at the two ends of the window

Round 26's fourth warning. `Reconciler.replacementBlocker` exempts a drain
already in flight — *"the container it would have saved is gone or going"* —
which is true from the stop onwards and false for every pass before it. On a
populated proxy that window is hours, and an orchestrator upgrade restaging the
asset directory (or a secret rotated behind its reference) inside it still
committed the teardown into a permanently refusing create.

So `DrainController.letGoAndStop` asks `Node.checkWorkload` again at the entry to
steps 6 and 7 — `REPLACEMENT` only, `RETRYABLE`, and *before* the deregistration
rather than before the stop, so a refusal costs no proxy churn. Two properties
worth keeping:

- **The subject carries the spec** (`DrainSubject.replacementSpec`), not a
  closure, so the two askers cannot disagree about what is being asked.
- **`Node.checkWorkload` is the create's *spec* derivation, not the whole
  create.** `LocalNode.ensureWorkload` also runs `prepareHostPaths`, which can
  refuse permanently on an unmounted or read-only volume/log root and cannot be
  checked without creating something. That residual is named in both KDocs rather
  than approximated: a writability probe is a *second* derivation and can refuse a
  create the real one would accept, which would freeze every replacement on that
  host.

## Still open: the wait itself is unbounded

The lever fixes the *trigger*. It does not bound the *wait*: a proxy replacement
on a fleet that never empties still seals and waits for ever, and an operator who
wants the new build has no way to say "take the outage now" or "give up". A
delete has the same shape and there it is correct. Ideas considered and not
written: a bounded replacement drain that parks and releases the seal (needs a
sticky give-up state, since the ladder re-enters every pass), or a declared
maintenance window. Raise it with a human before building either.

See [[level-triggered-seal]] for the compensation obligation this sits beside,
and [[audit-remedies-are-hypotheses]] for re-deriving a prescribed remedy.
