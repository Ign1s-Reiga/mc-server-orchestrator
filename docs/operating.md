# Operating notes

Behaviours that are deliberate, correct, and surprising. Each one has bitten a
drain audit or would have; each one is written here because it currently lives
only in a KDoc that nobody outside the code will open.

The theme they share: this orchestrator would rather leave a container running
and tell you about it than stop one it cannot vouch for. Several of the notes
below are that policy meeting a configuration where it has no exit. The
temptation in every case is to stop the container by hand. That is the one
action the whole drain protocol exists to make unnecessary, and it is the only
one that loses a world.

---

## 1. A persistent server with RCON disabled cannot be deleted

If a `PaperServer` has persistent storage and `spec.network.rcon` is disabled,
its drain cannot finish, so `DELETE` never completes and the container keeps
running.

This is not a bug. Stopping a server that holds a world requires evidence the
world reached disk, evidence means the server saying so, and RCON is the only
channel that can say it. Without RCON the orchestrator has a choice between
stopping on no evidence and not stopping. It does not stop.

The status says so — look for `NEEDS_ATTENTION` and a message ending:

> To retire it, save the world and stop the container yourself; the teardown
> completes on its own once a stopped container is observed. To keep it, revert
> `spec.network.rcon` and the server returns to running.

Both exits work. Re-enabling RCON is the one that keeps the orchestrator in
charge of the save.

## 2. Reverting an edit stops working once the stop has been dispatched

Editing a definition in a way that changes the container's shape starts a
replacement drain. Reverting the edit withdraws it — **until the stop request
has actually left for the runtime.**

After that point the revert is honoured one step later rather than immediately:
the drain finishes the container it already signalled, the teardown removes it,
and the reverted definition is applied by the create on the following pass. You
get what you asked for; you do not get the original container back.

The alternative would be to forget that a stop was dispatched, which is how
players get routed onto a process that has already run its shutdown save. The
orchestrator records the dispatch precisely so it cannot do that.

## 3. Three modules must agree on two hours

How long a stop may wait is decided by four constants, derived independently in
three modules. Only three of them cap a grace period; the fourth caps the save
timeout, and it is in the relation because the ceiling has a floor under it:

| Module | Constant | Caps | Value |
|---|---|---|---|
| `:schema` | `PaperServerDefaults.MAX_STOP_GRACE_PERIOD` | grace period | 2h |
| `:core` | `StopGraceCeiling.MAX` | grace period | borrowed from the above |
| `:cri` | `CriTimeouts.stopDeadlineCap` | the stop *call* | 2h, declared independently |
| `:schema` | `SpecBounds.MAX_SAVE_TIMEOUT` | save timeout | 1h |

The relation that matters is that **nothing a node can be handed exceeds the
deadline `:cri` will wait for it**, and it holds **at equality between
independent literals.**

The save timeout is in the relation and it is easy to miss. `StopGraceCeiling`
puts a *floor* under its own ceiling — it will not cap a grace period below the
save it has to cover — so once a save timeout passes `MAX - margin` the
effective ceiling stops being two hours and rises with the save timeout. That
is why the bound to check is `ceilingFor(MAX_SAVE_TIMEOUT)` and not `MAX`.

The deadline a stop call runs under is `min(grace, cap) + slack`, so a grace
period above the cap does **not** by itself mean the call gives up first —
there is a `deadlineSlack`-wide band above the cap (30s on the shipped default)
where the grace still expires first and containerd does reach the kill. The
condition that matters is **which of the two expires first**, not whether the
grace is over the cap.

Past that band the client stops waiting before the runtime reaches its kill —
and containerd does not re-deliver the stop signal on a re-issue, so re-issuing
with the same grace period can never finish it. The drain would retry for ever,
report itself, and stop nothing.

**You should never see that, because the orchestrator refuses to start
instead.** Wiring a node checks the relation against the cap its own CRI client
was built with, and a mismatch fails startup with `cannot start:` and a message
naming which constant to move. Nothing has been reconciled at that point, so
nothing is half-done: containers already running keep running, unmanaged, until
you fix the constant and start again.

So this is no longer a trap, but it is still the one place where modules that
each declare independence from the others have to agree for a loop to
terminate. Move any of them deliberately. Raising `stopDeadlineCap` is the safe
direction; lowering the others is constrained, because `SpecBounds` will not let
the grace ceiling fall below the save timeout plus its margin.

## 4. A backend has a seal and a router, or neither

`PaperDrainSubject` is constructed with one object passed as **both** its seal
and its router (`Reconciler.drain`). Several safety arguments depend on the
resulting equality — `seal != null` exactly when `router != null` — most
notably the rule that lets a routerless drain finish when its seal cannot be
asserted.

Nothing in the types enforces it. A change that supplies them separately
silently widens a waiver that was justified by their being the same object.

`ProxyDrainSubject` has a seal and no router at all, which is what makes the
proxy's own drain the routerless case.

## 5. A proxy has no save timeout, and that disarms a floor

`ProxyDrainSubject.saveTimeout` is hard-coded to `Duration.ZERO`, because a
proxy holds no world and has nothing to flush.

That zero is also the floor under `StopGraceCeiling`, which normally refuses to
cap a grace period below the save it has to cover. For a proxy there is nothing
to cover, so the floor is inert — correctly.

It is the single line a future "proxies can save something" change would have to
notice. Changing `ProxyDrainSubject` alone would remove the floor without
touching `Node.kt` or any test of it.

## 6. A drain is reported two ways, and one of them counts rather than waits

`NEEDS_ATTENTION` fires for a drain in either of two independent cases, and it
is worth knowing which one you are looking at because they read differently.

**It has been failing for a while.** One fault has stood for
`drainAttentionAfter` — fifteen minutes by default. The message names the
failure and says how long it has been true.

**It fails more often than it recovers.** This one exists because the first
cannot see a fault that keeps clearing: a control endpoint that fails on one
pass and behaves on the next never leaves a failure standing anywhere, and every
recovery deletes the record and its clock. So a running total is kept instead —
a fault adds one, a pass that finds the server healthy takes one away, floored
at zero — and the flag fires when it reaches six. The message says *"keeps
failing and recovering"* and quotes a count rather than a duration.

The arithmetic is the whole rule and it is meant to be checkable in your head:
**the total only grows while the drain is failing more often than it is
working.**

The surprise worth stating: **a fault that is present on exactly half the passes
sits at the crossover and is not reported.** That is deliberate. Buying it would
mean the decrement being smaller than the increment, and then no sentence
describes where the threshold is. In practice an intermittent fault is not a
metronome, so a genuine one-in-two fault does wander over the line eventually;
an exactly alternating one never does.

Neither case stops anything. The container keeps running and the loop keeps
retrying in both, which is what makes the flag safe to alert on.

---

## What to do when a drain will not finish

In order:

1. **Read `status.failure` and the conditions.** A parked drain says why, and
   the message names the remedy. `DRAIN_BLOCKED` means players are still
   connected and nothing is wrong.
2. **Check whether it is waiting on you.** Note 1 is the common case. A missing
   secret, an unreachable proxy control endpoint and a full fleet all report
   distinctly.
   If the message says the drain *keeps failing and recovering*, `status.failure`
   may be empty or may hold a fault that has already cleared — see note 6. Look
   at the logs for the whole period rather than at the one failure on the status.
3. **Edit the definition.** A generation bump is the documented lever for a
   permanently failed drain, and for several states it is the only one.
4. **If you stop a container by hand, save the world first.** The teardown
   completes on its own once a stopped container is observed, so a manual stop
   is recoverable — but only the save makes it safe.
