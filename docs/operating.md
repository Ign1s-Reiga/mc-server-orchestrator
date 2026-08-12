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

## 1. A persistent server whose save cannot be confirmed cannot be deleted

If a `PaperServer` has persistent storage and its world save cannot be
confirmed, its drain cannot finish, so `DELETE` never completes and the
container keeps running.

This is not a bug. Stopping a server that holds a world requires evidence the
world reached disk, evidence means the server saying so, and RCON is the only
channel that can say it. Without that evidence the orchestrator has a choice
between stopping on no evidence and not stopping. It does not stop.

**RCON is standard, so this is no longer something you can declare your way
into** — but configuring RCON is not the same as RCON answering. It dispatches
onto the game's main thread, so the ways to arrive here are a wedged server, a
long world-generation pass, or a password the server refuses. All three leave a
fully configured server unable to say the save finished.

The status says so — look for `NEEDS_ATTENTION` and a message ending:

> To retire it, save the world and stop the container yourself; the teardown
> completes on its own once a stopped container is observed. To keep it, revert
> `spec.network.rcon` and the server returns to running.

The second exit applies when an edit to `spec.network.rcon` is what asked for the
recreate. When the cause is a server that has stopped answering, the way out is
the first one.

Both exits work **on a delete**, which is the case this note is about: a
terminating definition keeps reconciling, so the loop is still watching and
notices the moment you stop the container yourself.

### The same stall reached by an edit does not clear itself

An edit that changes the container's shape — enabling `spec.network.rcon` is the
usual one — reaches the identical stall on a server that has no save channel, and
prints the identical advice. **On that path the advice is currently wrong.** The
definition is not terminating, so passes stop
(`Reconciler.kt:3307`, `permanentFailureStopsPasses`), nothing observes the
container you stopped, and the teardown never runs. The status goes on reporting
`CONTAINER_RUNNING` and `DRAIN_STALLED` over a container that exited cleanly.

Tracked as [issue #1](https://github.com/Ign1s-Reiga/mc-server-orchestrator/issues/1).

Until it is fixed, either exit still works, but you have to take it deliberately:

- **`DELETE` the server.** A terminating definition lifts the gate, the stopped
  container is observed on the next pass, teardown finishes and the name is freed.
- **Make a further spec change.** The gate also requires the observed generation
  to equal the definition's, so any edit that actually alters the spec resumes
  passes. Re-sending an identical definition does not — the generation only moves
  when the spec differs.

Note the ordering this implies for RCON: its settings apply **when the server is
created**. Changing them on a persistent server whose container has stopped
answering cannot take effect, because the change applies to the next container
and the current one cannot be drained without the channel that is not answering.

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
at zero — and the flag fires when it reaches six **and** the total has been above
zero for the same fifteen minutes. The message says *"keeps failing and
recovering"* and quotes a count rather than a duration.

Both halves matter. Six consecutive failures retry a second apart at first, so
the count alone would reach six inside half a minute and turn one containerd
blip into an alert. The fifteen minutes is what keeps this arm to the faults the
first one cannot see, rather than to the ones it has not got to yet.

The arithmetic is the whole rule and it is meant to be checkable in your head:
**the total only grows while the drain is failing more often than it is
working.**

The surprise worth stating: **this flag is reliable when a drain is failing more
often than it is working, and increasingly unlikely to fire below that.** It is
not a promise of silence — below half the total can still wander up to six — but
the flag also needs the total to *stay* above zero for the whole fifteen minutes,
and a recovering pass resets the retry delay to about a second, so fifteen
minutes is a long unbroken run of passes. Below half, the chance of a run both
long enough and unbroken enough falls away quickly as the run gets longer.

**No figure is given for how long that takes, deliberately.** It is not a
property of the orchestrator: it depends on the pattern of the underlying fault
and on how the loop happens to be scheduling that server, and any number quoted
here would be a number for a model rather than for your fleet. Earlier drafts of
this note carried two, and both were wrong by a wide margin.

What to take from it: **silence on this flag is not evidence of health for a
fault that is intermittent enough.** If you have a specific fault in mind and
want a faster signal, alert on the log line for it rather than on this flag.

Neither case stops anything. The container keeps running and the loop keeps
retrying in both, which is what makes the flag safe to alert on.

## 7. `status.storage` is what was seen, so it can be absent — and it never names a volume

`status.storage` reports the **container**, not your definition. `persistent` is
read back off the label the loop put on the workload when it created it, so a
server whose `spec.storage.mode` you have just edited keeps reporting what the
container that is actually running was built with, until that container is
replaced. That is the point: the previous behaviour reported the edit back at
you, which is useless for telling a half-applied change from a finished one.

Two consequences an operator meets directly.

**The whole block can be missing, and missing means nothing has been observed.**
A server the loop has not yet seen a workload for has no `storage` at all, and
`VOLUME_BOUND` reads `Unknown` rather than `False`. Do not read either absence as
"this server has no volume" — that is a different sentence, and only it would
tell you to stop looking for a world. `False` on `VOLUME_BOUND` means the loop
looked and there is nothing bound; `Unknown` means it has not looked yet.

**`volumeName` fills in as containers are replaced, not when you upgrade.** It is
read off a label the orchestrator puts on a container when it creates it, so a
container that was already running before this version carries none — and it is
*not* recreated to gain one, because labels are not part of the fingerprint that
decides a replacement. Nothing restarts on the upgrade; the field simply arrives
for each server the next time it is replaced for some reason of its own.

So an empty `volumeName` on a long-running server is expected and is **not** a
missing volume. The name is in your definition as `spec.storage.volume.name`,
defaulting to `metadata.name`, and `spec.storage.mode` is what answers "does this
server have a volume at all".

One deliberate asymmetry worth knowing: a server switched to `ephemeral` **keeps**
the last volume name it reported. That is not staleness, it is the point — it is
the only record of which volume still holds the world the replacement stopped
mounting, and it is where recovery starts.

---

## What to do when a drain will not finish

In order:

1. **Read `status.failure` and the conditions.** A parked drain says why, and
   the message names the remedy. `DRAIN_BLOCKED` means players are still
   connected and nothing is wrong.
2. **Check whether it is waiting on you.** Note 1 is the common case. A missing
   secret, an unreachable proxy control endpoint and a full fleet all report
   distinctly. So does the one that looks like nothing: a proxy whose container
   holds a control token that is no longer the one being sent answers its
   handshake perfectly — `control.reachable` and `control.compatible` are both
   true — while every seal, transfer and deregistration behind it is refused.
   That reads as `control.credential: REJECTED` and `control.usable: false`, and
   the remedy is to align the token, which needs no definition edit. Rotating the
   secret behind `spec.control.tokenSecret` does **not** recreate the container:
   only its coordinates are in the spec hash, deliberately, so a rotation cannot
   restart the fleet's front door on its own.
   **Putting the old token back is the remedy that always works**, and it is worth
   knowing why the other one is conditional. Getting the container onto a *new*
   token means recreating it; the only orchestrator-driven recreate is a
   spec-hash change; and the replacement drain that starts has to seal the proxy
   through the endpoint that is refusing. With players connected that seal is a
   precondition, so the drain parks at step 2 — the front door keeps serving,
   nothing is lost, and reverting the secret releases it. On a proxy the loop has
   just pinged as **empty**, the seal is waived and the replacement goes through
   to the stop. So a deliberate rotation (a leaked token) either waits for an
   empty window, or restores the old value first to get control back and is
   rotated from there. What does not work is editing the definition and hoping:
   with players on, that is a parked drain until the secret goes back.
   If the message says the drain *keeps failing and recovering*, `status.failure`
   may be empty or may hold a fault that has already cleared — see note 6. Look
   at the logs for the whole period rather than at the one failure on the status.
3. **Edit the definition.** A generation bump is the documented lever for a
   permanently failed drain, and for several states it is the only one.
4. **If you stop a container by hand, save the world first.** The teardown
   completes on its own once a stopped container is observed, so a manual stop
   is recoverable — but only the save makes it safe.
