---
name: level-triggered-seal
description: Why the proxy seal is asserted rather than issued, the two rules that look alike and are not (sealsBackend vs drainInitiated), and the single-point argument that replaced "one guard for six states"
metadata:
  type: project
---

Effects on a **third party that outlives an abort** cannot be issued as events.
That is the whole design of the proxy side of the drain, ruled before
implementation and confirmed by the tests.

## The seal is asserted every pass, and nobody ever un-seals

There is no unseal operation, on the wire or in `:core`. `PUT
/v1/backends/{name}` states registration *and* admission, and it is called on
every pass of every state that depends on it. An abort restores joins by simply
not asserting a seal any more; a proxy restart is repaired by the next
assertion. Neither needs an edge somebody has to remember to write.

**The proxy's own reconcile pass is the load-bearing half**, not the backend's.
A *permanent* drain abort freezes the backend's status and
`isBlockedByPermanentFailure` stops its passes entirely — so only the proxy's
sweep can restore joins to it. Without the sweep the failure mode is a server
that is running, invisible and unreachable, for ever.

**Deregistration is the one step that cannot be level-triggered** — it is the
last thing before the stop, so "assert it every pass" would mean asserting it
from states that must not reach it. It gets an explicit re-registration edge on
the abort path out of `DEREGISTERED`, in `abort`/`blocked`. A `NodeException`
escaping the controller skips that edge, which is why the stop is now caught
inside `DrainController` and turned into an abort.

## Two rules that look alike and answer different questions

- `DrainState.sealsBackend()` — *is this drain holding the backend out of
  routing.* False for `DRAIN_FAILED`: a drain that has stopped advancing is not
  going to move those players.
- `PaperServerStatus.drainInitiated` — *may this server receive somebody else's
  players.* True for `DRAIN_FAILED`, because a server parked on a retryable
  abort will try to stop again.

Using `drainInitiated` for the seal was a real defect the tests caught: a parked
backend stayed sealed for ever. They are opposite answers about the same state
and the plausible-looking substitution is wrong in both directions.

## The single-point argument that replaced "one guard for six states"

The old argument for wrapping every state in `requireEmpty` was that there was
one thing to audit. What replaces it is narrower, not looser:

> **No path reaches `Node.stopWorkload` except through `requireEmpty` followed by
> `mayStop`.** Steps 2–4 have no stop call and no edge to `STOPPING` that does
> not pass through `SAVING`, so they cannot lose data however wrong they are;
> `stop()` re-asserts `mayStop` itself as a backstop.

## The gate is the workload's own SLP, and a proxy count is never a decision

`backends[].players` cannot see a client connected straight to the backend port,
and whether backends are firewalled is a deployment property this code cannot
assert. It is corroboration and a log line. The trap is subtler than reading it
at the gate: **reporting `Progressed` from a sweep's `remaining`** resets the
backoff on every pass of a drain getting nowhere, and prints "every player has
been moved" about a populated server. A sweep in flight is always `Waiting`.

## Rulings I made that a human may overrule

1. **`DRAIN_FAILED` unseals.** A blocked-with-a-proxy drain therefore takes new
   players while parked. The alternative is an invisible running server, which
   the audit named as the harm.
2. **More than one proxy claiming a backend is a retryable failure on the
   *backend's* status**, and the container is not created while it holds. The
   drain of an already-running one still proceeds through the lexicographically
   first proxy — a delete must always be possible — so the second proxy keeps
   routing during that drain. Known gap, flagged.
3. **The proxy sweep deregisters registrations whose selector no longer
   matches.** That is step 6 performed by a sweep, and it is safe only because
   the plugin refuses `DELETE` with `BACKEND_OCCUPIED` and has no force flag.
4. **At the transfer limit the abort is `RETRYABLE` and escalates by the ordinary
   threshold**, with no exemption added to `escalates()`. Adding one is the
   mechanism that produced two earlier audit findings.

See [[standalone-drain-decision]] for what this supersedes and
[[blocked-is-not-failed]] for the failure/block split it rests on.
