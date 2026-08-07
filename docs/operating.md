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

A stop's grace period is capped in three places that each derive it
independently:

| Module | Constant | Value |
|---|---|---|
| `:schema` | `PaperServerDefaults.MAX_STOP_GRACE_PERIOD` | 2h |
| `:core` | `StopGraceCeiling.MAX` | borrowed from the above |
| `:cri` | `CriTimeouts.stopDeadlineCap` | 2h, declared independently |

The relation that matters is `StopGraceCeiling.MAX <= stopDeadlineCap`, and it
currently holds **at equality between two independent literals.**

The deadline a stop call runs under is `min(grace, cap) + slack`, so a grace
period above the cap does **not** by itself mean the call gives up first —
there is a `deadlineSlack`-wide band above the cap (30s on the shipped default)
where the grace still expires first and containerd does reach the kill. The
condition that matters is **which of the two expires first**, not whether the
grace is over the cap.

Past that band the client stops waiting before the runtime reaches its kill —
and containerd does not re-deliver the stop signal on a re-issue, so re-issuing
with the same grace period can never finish it. The drain retries for ever,
reports itself, and stops nothing.

Nothing is lost if that happens. But it is the one place where three modules
that each declare independence from the others have to agree for a loop to
terminate. Move any of the three deliberately.

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

---

## What to do when a drain will not finish

In order:

1. **Read `status.failure` and the conditions.** A parked drain says why, and
   the message names the remedy. `DRAIN_BLOCKED` means players are still
   connected and nothing is wrong.
2. **Check whether it is waiting on you.** Note 1 is the common case. A missing
   secret, an unreachable proxy control endpoint and a full fleet all report
   distinctly.
3. **Edit the definition.** A generation bump is the documented lever for a
   permanently failed drain, and for several states it is the only one.
4. **If you stop a container by hand, save the world first.** The teardown
   completes on its own once a stopped container is observed, so a manual stop
   is recoverable — but only the save makes it safe.
