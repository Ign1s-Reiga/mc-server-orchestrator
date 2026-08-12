# How the loop works

The Kubernetes reconcile idea, reimplemented directly: you write down the servers
you want, and a loop repeatedly compares that against what it can observe and
does the next single thing that closes the gap. There is no apiserver, no CRD, no
controller-runtime — the idea is the only thing borrowed.

This document is the contract the loop holds itself to. For what it *reports*,
see `docs/server-lifecycle.md`; for the behaviours that surprise operators, see
`docs/operating.md`.

---

## Finding work

Two sources feed one queue:

1. **The change feed** — the store records every desired-state write, and the
   loop polls the changes since its cursor. This is the fast path, and it is an
   *optimisation*: it is allowed to expire and send the loop back to a full
   resync.
2. **A full resync**, on a period. Correctness rests on this one. Anything the
   change feed loses, the resync finds.

The cursor is read *before* the startup resync, so nothing that happens between
the two is missed. In-flight drains are resumed before anything else — a drain
that was half-done when the process stopped is the one thing that cannot wait for
its turn in the queue.

Work is spread over a fixed number of workers, each taking one server at a time.
A server being worked on is not handed to a second worker, so nothing races
against itself.

---

## The rules a pass obeys

**Reconcile is idempotent.** Running a pass repeatedly against the same desired
and observed state must not accumulate side effects: no duplicate containers, no
repeated pulls, no repeated save requests. Observed state is recorded after every
pass, which is what makes the next one able to tell "already did this" from
"never started".

**A pass never blocks for long.** Waiting on a container state is a requeue with a
backoff, never a sleep in place. A worker parked on a slow operation is a worker
that cannot serve the servers behind it. A pass that fails retryably comes back
on an exponential backoff; one that succeeds comes back on the ordinary delay.

**Failures are classified, never swallowed.** Retryable failures requeue.
Permanent ones surface on the server's observed status and stop the loop retrying
— which is a statement about the *loop*, never about the container. A limit is
somewhere you stop trying; it is not somewhere you stop a Minecraft server.

**Every container operation goes through the `Node` abstraction.** Nothing outside
the single-host `Node` implementation may assume the container is local. Today
there is exactly one node and the scheduler that picks it is trivial, but both
are real interfaces with real call sites, and collapsing them is the change this
project explicitly does not accept.

---

## Deciding what to do

For a server that should exist, roughly in order: pull the image, bind the
volume, create the sandbox and container, start it, watch for readiness, then
keep observing. Each of those is a phase a client can see.

For a server that already exists, the question is whether the container still
matches the definition. That comparison is a **spec hash**: the container carries
the hash it was built from, and any difference makes the cause
`DrainCause.REPLACEMENT`. Which fields feed that hash — and therefore which edits
cost a drain — is listed in `api/API.md` §5.

A definition that is terminating drains for `DELETION` instead, and the name is
freed only once the containers are confirmed gone.

---

## Stopping is a drain, always

There is no unconditional container stop anywhere in the codebase, and that is
the single invariant everything else is arranged around. Scale-down, restart,
reschedule and "apply a changed spec" are all the same operation underneath:
drain, then replace.

The seven steps are in the README, and the states they surface in
`docs/server-lifecycle.md`. The properties that matter to anyone changing this
code:

- **A failed drain leaves the server running.** There is no edge from
  `DRAIN_FAILED` to a stop. Reaching a retry limit is never a reason to force one.
- **A save must be confirmed, not merely requested.** Sending the request is not
  evidence. The container stop timeout is a last-resort net for a container
  disappearing outside the protocol, never the normal save path, and the grace
  period must always outlast the expected save.
- **A dispatched stop is recorded before it is issued.** A pass cancelled between
  issuing a side effect and recording it would lose the record, and the next
  process would send a second save request to a server that already ran one — so
  that record is written under `NonCancellable`, which bounds a shutdown by one
  store write rather than by a container operation.

The authoritative treatment is in [`state-machine.md`](state-machine.md) — every
state, what advances it and its timeout — and [`failure-modes.md`](failure-modes.md),
whose numbered items source across `:schema`, `:core` and `:velocity-plugin` cites
directly. Those numbers are load-bearing: renumbering an item silently rewrites
what a comment in the reconciler claims.

---

## Where it stops on its own

A permanent failure on a **non-terminating** definition stops further passes on
that server. The intent is that a server nobody can help should not be re-tried
for ever, and the exemption for terminating definitions exists so that a delete
keeps reconciling — the loop has to be able to notice an operator stopping a
container by hand.

That exemption is currently one branch short: the same stall reached by an *edit*
also prints advice that assumes the loop is still watching, and it is not. See
`docs/operating.md` note 1 and
[issue #1](https://github.com/Ign1s-Reiga/mc-server-orchestrator/issues/1).

The gate also requires the observed generation to equal the definition's, so a
real spec change lifts it — which is the practical way to resume a server that has
stopped taking passes.

---

## Testing it

The reconcile logic is exercised against a fake node in `:core`'s unit tests and
against a real containerd in `:app`'s integration suite. Two mutation harnesses
guard the parts where a passing test could still be measuring nothing:

```bash
scripts/dev/drain-wiring-mutations.sh     # 96 mutations of the drain wiring
scripts/dev/control-plugin-mutations.sh   # 12 mutations of the proxy control plugin
```

Each plants a deliberate defect, rebuilds, and fails unless the specific test that
claims to catch it does. A rule that nothing can break is not a rule — and drain
code is where a test that asserts nothing is most expensive.
