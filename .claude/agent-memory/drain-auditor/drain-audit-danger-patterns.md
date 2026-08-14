---
name: drain-audit-danger-patterns
description: Reusable audit checks for stop/drain safety in this repo — the non-obvious failure shapes found in real reviews, beyond the forbidden list in failure-modes.md
metadata:
  type: project
---

Audit heuristics that go beyond the seven forbidden implementations in
`docs/failure-modes.md`. Each was found in a
real review of this codebase and each looks correct on first reading.

**Why:** the drain skill's forbidden list catches the obvious shapes (force stop
after timeout, save-requested-vs-completed). The failures that actually get
merged are the ones below, which pass every check in that list.

**How to apply:** run these against every change that touches `DrainController`,
`Reconciler.teardown`, `Node.stopWorkload`/`removeWorkload` or `PaperServerAgent`.

1. **Stale save evidence.** `worldSaved` is a boolean latched once. On a
   standalone server nothing seals joins, so a drain can confirm a save, then
   observe players, abort, sit in `DRAIN_FAILED` for hours while people play, and
   resume straight into the stop on the strength of the *old* confirmation. Ask
   not "is `worldSaved` true" but "was the save confirmed after the last observed
   non-zero occupancy". The same applies across a loop restart, where drain state
   is rehydrated from the store.
2. **Which exception a save timeout actually raises.** `SaveOutcome.Unconfirmed`
   (permanent, request recorded) versus `NotDelivered` (retryable, request *not*
   recorded, so it is re-sent) is decided by `NodeException.Timeout` vs `Busy`,
   which is decided by the gRPC status code containerd returns for an `ExecSync`
   that outran its own timeout. If containerd answers `UNKNOWN` rather than
   `DEADLINE_EXCEEDED`, the "never re-send a save" rule silently does not hold.
   Verify against a real containerd, not against the type names. (Round 5: it
   answers `DEADLINE_EXCEEDED`, promptly, with `failed to exec in container:
   timeout Ns exceeded` — the *same code* as a transport deadline. `:cri` now
   separates them by elapsed time, never message text.)
3. **A failed command is not a delivered request.** `rcon-cli` exiting non-zero
   because it could not connect is classified the same as a save that was
   accepted and never confirmed. The first must be retryable; treating it as
   "issued" wedges the server permanently on a transient error.
4. **The remediation path must itself be drainable.** If the documented fix for
   an undrainable server is "edit the definition" (e.g. enable RCON), check that
   the edit does not require a drain of the *old* container using the *new*
   definition's capabilities. `Reconciler.Pass.agent` is built from the desired
   definition while the exec runs against the running container.
5. **Storage mode is in the spec hash.** Flipping `persistent` → `ephemeral`
   triggers a replacement drain that skips the save entirely and stops the
   container. It is a legitimate-looking edit that is also a save-skipping force
   stop. Treat it as a stop path — and check the *delete* that follows a refused
   edit, which is never refused and runs under the edited definition.
6. **The side effect outlives the record.** A save request is issued, then the
   status write conflicts and the `saveRequestedAt` stamp is dropped. Any
   "recorded so it is never repeated" claim needs the record to be written
   before, or atomically with, the side effect.
7. **"No player was *observed* since" is weaker than "no player *was* there
   since".** This is the third door on item 1, and the one that survives a fix
   for the other two. Voiding the confirmation when a probe reports somebody
   online, and when the container has restarted, still leaves every window in
   which the loop was *not looking*: a probe that fails (the exec is rejected,
   `mc-monitor` does not answer) while the game server's listener is perfectly
   healthy, or the orchestrator process being down. Those windows preserve the
   confirmation and grow to the backoff cap or to a whole outage. A save
   confirmation is only good if it is backed by an **unbroken chain of positive
   zero-player observations** — so a pass that *cannot confirm* zero players must
   void the evidence exactly as a pass that saw a player does, and a gap between
   the recorded `observedAt` and now must do the same. Re-saving is cheap;
   stopping on a confirmation from before an unobserved play session is not.
8. **A retry backoff is only a cap if the retries are consecutive.**
   `WorkQueue.succeeded` clears the attempt counter, and `ReconcileOutcome.Progressed`
   calls it — as does `Waiting`. A failure loop that alternates `Progressed` (the
   `DRAIN_FAILED` resume moves state, so it counts as progress) with `Retry` (the
   step then fails) resets the counter every other pass and never leaves attempt 1.
   What reads as "retries at the 5-minute cap forever" is a ~2-second hot loop
   issuing an exec and a store write every pass, for ever. Before accepting any
   "it backs off" argument, check whether a state change is interleaved with the
   failure. (Closed as of round 4: the `DRAIN_FAILED` resume runs the resumed
   state in the same pass and reports *its* outcome, and `Reconciler.write`
   replaces a `Progressed` with the conflict's `Retry`, so a store conflict
   cannot drive the alternation either.)
9. **The guard and the trigger drink from the same well.** A "refuse to tear down
   if anything is still running inside" check is only as good as the data that
   says what is inside. When that data is also what produced the "the container
   is already gone" verdict that routed the code to the teardown, the two
   failures are perfectly correlated and the guard is empty exactly when it is
   needed. In this repo both `LocalNode.observationOf` and the teardown guard
   used to read `PodSandboxStatusResponse.containers_statuses` — a
   runtime-dependent CRI field — while `ListContainers(sandboxId)` is the
   mandatory call. Fixed in round 4: `LocalNode.containersIn` enumerates from
   `ListContainers` and uses the optional field only as a per-status *overlay*.
   Before accepting any "it refuses if occupied" argument, ask what the check
   returns if the runtime simply says nothing, and whether that same silence is
   what routed the code here. Related: a state filter of `== RUNNING` treats
   `UNKNOWN` as "not running", which contradicts `DrainController`'s own
   "UNKNOWN is not a reason to act".
10. **A "safe" fallback that launders the input it was meant to avoid.** Falling
   back from a workload label to *observed status* looks like falling back to an
   observation. It is not, if the status field is itself derived from the desired
   definition and rewritten every pass — then it is the edited definition with an
   extra hop. Check what *writes* the field, not what reads it.
   `StorageStatus.persistent` is computed from `definition.spec.storage`, so
   `previous.storage.persistent` carries the *new* value from the first pass
   after an edit onward. (Dropped in round 4; `holdsWorldData = label ?: true`.)
11. **A resume that clears the failure resets the attempt counter.** Any state
   machine that does `copy(failure = null)` on re-entry and then fails again in
   the same pass records `attempts = 1` for ever and restamps `occurredAt`.
   Escalation and "how long has this been broken" reports read those fields and
   will lie. Escalate off an immutable anchor (`drain.startedAt`), and check that
   whatever the escalation *prints* is not the counter the resume just reset.
12. **"The loop wrote a status" is not "the loop observed zero players."** An
   evidence-chain witness taken from `status.observedAt` is refreshed by passes
   that never probed — a node failure, a placement refusal, a store conflict. It
   only closes because those paths are retryable and the backoff opens the gap
   within a few attempts. The witness that actually means something is the
   timestamp of the last successful probe. Also check the gap threshold against
   `statusHeartbeat`: an unchanged status is not rewritten, so `observedAt` can
   lag a watching loop by the whole heartbeat. The sound relationship is
   `stepInterval < saveEvidenceMaxGap << backoffCap`, and `saveEvidenceMaxGap`
   must be shorter than the time in which a player can join and make progress.
   (Closed in round 4 by `lastProbedAt = previous.players.observedAt`. When
   auditing that witness, enumerate every construction of the occupancy type —
   the property is "it is only ever built from a probe that answered" — and
   check that the status-unchanged skip cannot stale it, which it cannot while
   the occupancy timestamp is part of the compared status.)
13. **A "has this ever existed" guard is sticky, and stickiness is a wedge.**
   Fixing "an unreported container is not an absent one" by remembering that a
   container id was once seen (`runtimeIdentity` keeping `previous.containerId`
   when the observation carries none) makes the memory monotonic. It is then
   never true that the container is *legitimately* gone-but-sandbox-remaining —
   e.g. `removeWorkload` removed the container and then `stopSandbox` failed —
   and the drain refuses to finish for ever with a message asserting a live
   process that provably is not there. Whenever a guard is keyed on "we once saw
   X", find the path where X was deliberately destroyed by this loop and check
   that the record is retired there. Erring safe is right; erring safe *and*
   unrecoverable-without-`crictl` is a finding.
14. **A new abort that reuses the "a player was seen" evidence-voider.**
   `forgetSaveEvidence` clears `saveRequestedAt` as well as the confirmation,
   and clearing that field is what makes a delivered-but-unconfirmed save
   re-sendable. That is correct for the one case it was written for (a player
   was just observed, so the old request is worth nothing). Any *new* caller —
   a runtime that stopped reporting the container, an unrunnable probe — gets
   the re-send for free and silently un-wedges the permanent
   `DRAIN_SAVE_TIMEOUT` posture without a human. Check every call site of an
   evidence-voider against the *specific* justification in its doc comment, not
   against its name.
   **Status: closed at round 6.** `requireEmpty`'s unanswered branch and the
   `SANDBOX_ONLY` abort both use `forgetSaveConfirmation`; `forgetSaveEvidence`
   is called only from the two `probe.online > 0` branches. The check itself
   stays: re-run the call-site enumeration on every change, because the doc
   comment has been wrong before and its *count* of call sites is still stale.
15. **A reclassification that splits one phase into two can restart a timer.**
   Mapping a previously-single failure onto two `ServerPhase`s makes a flapping
   input alternate phases, and `draftStatus` restamps `lastTransitionAt` on every
   phase change. Anything measured from `lastTransitionAt` — here the startup
   timeout in `Reconciler.awaitJoinable` — then never elapses. Narrow in this
   repo because the timer prefers `observation.startedAt`, which containerd does
   report, but the shape generalises: whenever a change adds a second phase for
   an existing condition, grep for every deadline anchored on
   `lastTransitionAt`. It also turns a settled status into a store write per
   pass, which defeats the unchanged-status skip.
16. **Mapping a skill-doc row onto actors this deployment does not have.**
   `failure-modes.md` is written for proxy + in-container agent. When an
   implementer maps "the agent" onto containerd's `ExecSync` and "the server does
   not respond" onto the SLP probe, rows land on the wrong side of the state
   machine. Resolve the roles first: here the agent-equivalent is
   `mc-monitor`/`rcon-cli` *reaching* the server, and each row keys on a
   different one. Before accepting a "we diverge from the skill" flag, check
   whether the row is actually implemented somewhere else in the machine under a
   different trigger. See [[standalone-paper-drain-shape]] for the round-5
   ruling on "agent responds but the server does not".

17. **Splitting one field into two has an asymmetric safe direction, and the
   migration must be checked in both.** Two facts sharing one field
   discriminated by a flag (`saveRequestedAt` + `worldSaved`) split cleanly only
   if you also ask what a *stored* row becomes. One direction — a completed save
   read back as an outstanding request — wedges every in-flight drain across an
   upgrade and is loud. The other — an unconfirmed request read back as a
   confirmation — authorises a stop and is silent. Find the literal comparison
   that decides which branch a row takes (`document.string(FLAG) == "true"`) and
   prove that *every* value it does not recognise, including absent and
   corrupted, falls to the loud side. Then walk the migrated row through every
   state of the machine, not just the two the tests cover.
18. **A migration guard keyed on a live constant is not frozen.** A data
   migration that refuses rows whose encoding `!= PropertyDocument.ENCODING_VERSION`
   silently changes meaning the day that constant is bumped, and then refuses to
   open every store that skipped the upgrade. Migrations are history: their
   guards must compare against the literal they were written against. Same
   family as "never edited once shipped", but it evades that rule because
   nobody edits the file.
19. **Removing a field from `equals` is safe only if it cannot vary
   independently.** `Reconciler`'s write-skip is `status.copy(observedAt = ...) == previous`,
   so anything excluded from `equals` is invisible to "has this changed". A
   derived `val` in the class body (`worldSaved get() = worldSavedAt != null`) is
   excluded automatically and is fine *because* it is a function of a constructor
   property that is included. Whenever a field leaves the constructor, ask
   whether two materially different objects can now compare equal — and, in the
   other direction, whether a voider that was conditional and is now an
   unconditional `copy` turns an unchanged status into a store write per pass. It
   does not here, because the comparison is structural, but an identity check
   anywhere on that path would make it one.
20. **"That combination is impossible" has to be enforced by construction, and
   the doc saying where an impossible row *lands* has to be checked per state.**
   The disjointness of `saveRequestedAt` and `worldSavedAt` is real — `save()`
   returns early whenever a request is outstanding, so the one line that sets
   `worldSavedAt` is unreachable with one — but the KDoc claiming a both-set row
   "reaches the `saveRequestedAt` check and refuses to re-send" is only true in
   `SAVING`. In `DEREGISTERED` and `STOPPING` the confirmation wins and the
   container stops. Deliberately not `require`d, because a throwing decoder makes
   the row unreconcilable; that is the right call, but it means the prose is the
   only spec and the prose must be state-by-state.

21. **A durability test is only as strict as its most permissive fake.** A fake
   store taking an *uncontended* `Mutex` never suspends, so it never checks
   cancellation and lands a write the real store (`withContext(dispatcher)`,
   whose dispatch from a cancelled coroutine never runs the block) would drop. A
   fake node that never suspends at all keeps driving the runtime for a cancelled
   caller that gRPC would have refused. Either one makes a cancellation-durability
   test pass against code containing no shield. Before believing any green
   "the record survived" result, ask of every fake on the path: *where does this
   check cancellation, and is that at least as early as production?* Closed at
   round 7 by an `ensureActive()` on entry to `TestStore.guarded` and
   `FakeNode.check`, pinned by a contract test. Note the residual in the other
   direction: `FakeNode` checks *before* recording the call, so it can only model
   "never delivered", never "delivered then cancelled" — the tests get the second
   shape by cancelling from inside `onExec`, after the command is recorded.
22. **A `NonCancellable` write is worth nothing without the shutdown ordering
   that keeps its dependencies alive.** The shield around the save record only
   lands because `Main` closes the `Orchestrator` (and with it the store) *after*
   `loop.join()`, and because the single shutdown hook joins rather than racing.
   Move the close, add a second hook, or close the store from a parallel
   `use`, and the shielded write hits a closed store, throws `StoreException`
   from inside the region, and the record is lost exactly as before — with the
   coroutine already cancelled, so nothing retries it. Whenever you see
   `withContext(NonCancellable)`, find who owns the resource it writes to and
   prove that resource outlives the region.
23. **Write-ahead ("record the side effect before issuing it") is not the safe
   direction here, and its window is wider, not narrower.** Ruled at round 7 for
   `saveRequestedAt`. Recording first means a cancellation between the record and
   the exec leaves a server that got *no* save wedged in a permanent
   `DRAIN_SAVE_TIMEOUT` whose operator-facing message asserts a save was
   requested — false, and unwedgeable without a human. The gap it opens is at
   least a dispatch plus gRPC setup wide; the gap it closes needs cancellation to
   land inside one specific in-flight RPC. It also puts a second writer on a field
   whose disjointness is load-bearing. Prefer the benign repeat. The repeat is
   only benign while every side effect the drain issues is idempotent on the game
   side (`save-all flush` is) — the moment a drain issues a kick, a transfer or a
   proxy deregistration, this ruling must be re-taken.
24. **"It is a vanishing fraction of the window" is the argument to distrust, and
   "what is at risk in the resting state" is the one to make.** A proportion
   argument for leaving a hole open is the same shape as the one for closing it,
   and both are unfalsifiable at review. The question that decides it is what the
   failure *rests* as. The teardown partial-removal record (`Reconciler.teardown`,
   `containerId = null`) was upheld as open at round 7 — not because the write is
   a small slice of a window mostly inside `removeWorkload`, but because the
   container is already gone by then, so no world and no player is at risk; what
   is stranded is a sandbox and a store row, retryably, with a loud escalation at
   `drainAttentionAfter`. An identical-shaped hole on a path where the container
   is still *running* would not survive the same argument.

Related: [[standalone-paper-drain-shape]]

25. **A reason-keyed exclusion placed above a class check is a silent suppressor
    in the other direction.** `escalates` returns false for
    `DRAIN_NO_DESTINATION` *before* looking at the failure class, which is right
    — it stops "somebody is playing" becoming an alert every backoff interval,
    and it holds however a future call site classifies that reason. But the same
    ordering means a `DRAIN_NO_DESTINATION` that was ever classified `PERMANENT`
    would be a wedged, unretried drain that is never flagged, which is exactly
    the state the flag exists for. An exclusion whose justification is "this
    resolves itself" is only sound while the reason *cannot* be permanent, and
    nothing enforces that: `FailureStatus` takes reason and class as independent
    constructor args with no `init`. Whenever a reason-keyed exclusion outranks a
    class check, ask for the construction-site guard that makes the exclusion's
    premise true, rather than leaving the reporting rule to paper over it.
26. **When a gate returns before observing, a threshold is not delayed — it is
    deleted.** `Reconciler.Pass.isBlockedByPermanentFailure` returns
    `ReconcileOutcome.Failed` with *no status write* for a non-terminating
    server, so the pass that records a permanent abort writes the last status
    that server will ever have. Any signal computed at status-draft time from
    "has this been broken for N minutes" can therefore never appear: the resync
    keeps calling `reconcile`, the gate keeps short-circuiting, and the frozen
    status keeps whatever the threshold said at the instant it froze. Before
    accepting or proposing any "after N minutes" *reporting* rule, find the path
    that would cross the threshold and prove it still writes statuses. The
    delete case is the exception — the gate lifts while a delete is outstanding
    — and that asymmetry is what makes "immediate" the only correct answer for
    the replacement and relocation cases.
27. **Operator prose that asserts a runtime fact gets frozen with the status.**
    The permanent escalation text leads with "the server is still running and
    still joinable". True at the instant it is written for every path that
    reached it through `requireEmpty`'s `Joinable(0)`, but *not* for the
    permanently-unanswered-probe abort, which by definition never confirmed
    joinability — and, once the gate freezes the status, the sentence keeps
    asserting it for as long as the row exists. The direction happens to be
    safe here (an operator told players may be connected is *less* likely to
    kill the container by hand), which is the test to apply: for each path that
    can reach a message, ask what the message asserts, whether that path
    established it, and which way an operator errs if it has since stopped being
    true. A message that over-states availability errs safe; one that
    under-states it invites a manual force stop.

28. **A decode-time invariant's blast radius is the widest read that decodes it,
    not the row that carries it.** `FailureStatus.init` refusing a contradictory
    pair becomes `StoreException.Corrupt` via `rebuilding`, which sounds like
    "one server is unloadable". It is not: `SqliteStore.listServers` and
    `listByDrainState` `mapAll(::readServer)`, so the *first* bad row aborts the
    whole list. `ReconcileLoop.resync` and `resumeDrains` each catch
    `StoreException`, warn, and queue **nothing** — so one hand-edited row stops
    the loop finding any work at all, fleet-wide, and every in-flight drain
    halts. `:api`'s list endpoint and the SSE `resync` read the same call, so the
    operator loses the fleet view at the same moment. Before accepting any
    "blast radius is one server" argument for a decode-time `require`, find the
    list read and check whether it isolates per row. (Not a world-data path: a
    halted loop cannot stop a container. Migrations are safe — they work at
    `PropertyDocument` text level and never build the spec types, so the store
    still opens.)
29. **`ready` on a draining status is the freshness proof, and anything keyed on
    it inherits that.** `Reconciler` drafts a drain status with
    `ready = progress.occupancy != null && phase == RUNNING`, while
    `players = progress.occupancy ?: previous.players` is carried forward. So
    `players.online` is stale in general but **fresh exactly when `ready` is
    true**. That coupling is what makes the round-9 conformance property sound.
    It is undocumented and one line from breaking: change the drain path's
    `ready` to carry forward from `previous`, and every consumer that reads
    "still joinable" plus a player count starts reading a stale pair. Grep for
    that assignment before trusting any dashboard-level rule about occupancy.
30. **Making a read tolerant moves the failure from loud to quiet, and the
    *presentation* layer has to be moved with it.** Round 10: `:store` learned to
    charge an undecodable row to one server — an unreadable observation now
    leaves `StoredServer.status = null` with `StoredServer.unreadable` set. But
    `:api` draws its conclusions from `status == null`, so
    `ServerJson.displayState` renders that server `PENDING`, `detail` says
    "accepted; nothing observed yet", `needsAttention` is false, and the SSE
    `Versions.of` (status resourceVersion → null) fires an `updated` event that
    *animates* a draining server into "nothing observed yet". Before the change
    the same row was a loud fleet-wide failure. Whenever a read stops raising and
    starts annotating, enumerate every consumer that inferred something from the
    absence the annotation now shares, and check the direction: under-stating
    availability is what invites a manual `crictl stop` (item 27).
31. **The fleet-read guards are keyed on `StoreException`; not every failure a
    fleet read can produce is one.** `ReconcileLoop.resync` and `watchChanges`
    catch `StoreException` only, and `resyncPeriodically` has no guard at all —
    an escape there cancels the loop's `coroutineScope`, taking the workers and
    the poller with it, and `Orchestrator.run` does not restart it. `SqliteStore`
    converts JDBC failures in `Jdbc.query`, but a *column* read straight into a
    non-null Kotlin parameter does not go through that: `readDefinitionRow` →
    `resourceName(rows.getString("name"), …)` NPEs on a NULL name, and SQLite
    permits NULL in a `TEXT PRIMARY KEY` (`server_definition.name` is declared
    without `NOT NULL`). That turns the round-9 bug — a loop that retried
    fruitlessly every 5 minutes — into a process that dies inside `seed` on every
    start. Same hand-edited-row threat model as the decode failures the tolerant
    read was built for. Whenever a decode path is hardened against one exception
    type, ask what *else* that read can throw and whether the caller's catch is
    wide enough.

32. **A record that enables an *undo* has the opposite safe direction from one
    that suppresses a *repeat*.** Item 23 ruled write-ahead unsafe for
    `saveRequestedAt`, whose record exists so a delivered save is never re-sent —
    losing it costs one idempotent repeat, recording it early costs a false
    permanent wedge. `sealRequestedAt` and `deregisteredAt` are the same shape of
    field with the opposite purpose: their record is what tells a later pass there
    is something to *reverse*. Losing one leaves a backend sealed off from new
    joins, or deregistered, with nothing in the system knowing to put it back —
    silent, and it looks like a healthy running server. Do not generalise item 23
    across the `*RequestedAt` family. Better than inverting the rule: make the
    proxy-side fact **level-triggered** — re-asserted from the drain state every
    pass — so no record is load-bearing and there is nothing to lose.
33. **A forward-only state machine acquires a compensation obligation the moment
    a step gains an external counterparty, and has nowhere to put it.**
    `DrainState` has no reverse edge and `DRAIN_FAILED` has no body: correct while
    every step's only effect is on the container being drained. A seal and a
    deregistration are effects on a *third party* that outlive the abort. Before
    adding any step that changes state outside the workload, ask what the abort
    path restores it to, and check that the answer is not "nothing".
34. **Filling in a no-op state's body can reopen a closed danger pattern.** Item 8
    was closed because the `DRAIN_FAILED` resume runs the resumed state in the
    same pass and reports *its* outcome — sound while the resumed state either
    fails immediately or reaches the end. Give the intermediate states real work
    and the resume produces `Progressed` on one pass and the failure lands on the
    next, so `Progressed`/`Retry` alternate, `WorkQueue.succeeded` clears the
    attempt count every other pass, the backoff never grows, and `attempts` is
    pinned at 1 for ever (item 11). What was a closed audit item becomes a hot
    loop issuing real side effects at a live server. Re-run item 8 against every
    change that lengthens the path between a resume and its failure.
35. **A guard that was a correct *terminal* answer ends up on the wrong side of
    the work.** `requireEmpty` wraps every state from `SEALED` onward, which is
    right when players online means the drain has nowhere to go. It is exactly
    wrong once those states exist to *act* on the players: the destination search
    and the transfer would abort on the precondition that is supposed to trigger
    them. When a no-op becomes work, check which side of each guard it lands on —
    the guard did not move, so it will be wrong by default.
36. **A second, more convenient source of player counts that cannot see every
    connection.** Asking a proxy "how many players are on backend X" is one RPC
    against an exec, and it is wrong: a client connected straight to the backend
    port is invisible to the proxy and visible to SLP. The zero-player gate has to
    stay on the workload's own channel; any other source is corroboration, never a
    substitute. Whenever a new component knows something the drain currently
    establishes the expensive way, ask what that component *cannot* see.
37. **A shortcut that bypasses the state machine bypasses every external step in
    it.** `advance`'s `containerIsDown` branch jumps straight to `STOPPING` with
    `playersEvacuated = true`, which is safe for the container and skips all seven
    steps. That costs nothing while steps 2, 4 and 6 are no-ops; once they are
    real it leaves a third party holding stale registration for a workload that is
    about to be removed. Every early return in `advance` needs re-checking against
    each step that gains a body.
38. **A closed-set enum value that meant one thing under one deployment silently
    comes to mean two.** `DRAIN_NO_DESTINATION` means "waiting for people to log
    off, which resolves itself" today and will also mean "the fleet has no
    capacity anywhere", which does not. Every rule keyed on the value — an
    escalation exclusion, a decode-time `require`, a dashboard carve-out — was
    written for the first meaning and will silently cover the second. When a
    deployment gains an actor, enumerate the reason codes whose meaning was
    implicitly narrowed by that actor's absence, and split before the rules drift.

39. **Two derivations of the same flag — one from the condition, one from the
    field — drift at whichever branch was added last.** Round 11: `:api` renders
    `display.drainBlocked` from the `DRAIN_BLOCKED` *condition* (which encodes
    `drain.blocked != null && drain.failure == null`), while `ServerJson.detail()`
    branches on `status.drain?.blocked != null` raw, with the TERMINATING branch
    ordered above every failure branch. One payload can therefore say
    `drainBlocked: false` beside `detail: "waiting, not stuck"`. The general rule:
    when a precedence ("the failure wins") is chosen *instead of* a decode-time
    `require`, that precedence is the entire specification and has to be pinned by
    a test at **every** site that consults both — and it has to name *which*
    failure. `drain.failure` and `status.failure` are different fields, and a
    status-level failure sitting beside a drain-level block is reachable with no
    hand edit at all: `Reconciler.nodeFailure` drafts with `drain = previous.drain`
    and sets `failure`, so a blocked drain plus one unreachable node produces the
    pair the design calls impossible.
40. **A property-based conformance rule inherits the lifetime of the suppression
    it was derived against.** The round-9 carve-out `playersOnline > 0` was sound
    only because it was coextensive with the `DRAIN_NO_DESTINATION` escalation
    suppression. Delete the suppression and the identical carve-out silently
    widens from "excuses the one suppressed case" to "excuses any parked drain
    that happens to have players on it" — which then includes a permanent
    `DRAIN_SAVE_TIMEOUT`, the case the rule exists to catch. Re-keying it on the
    recorded fact (`drainBlocked`) was not a tidy-up; it was forced by the same
    change. Whenever a suppression is retired, grep for every rule whose
    antecedent was shaped to match it and re-derive rather than re-read.
41. **A migration that leaves derived data to "self-correct on the first pass"
    opens a window in which two consumers of the same row disagree.** V5 rewrites
    `drain.failure` into `drain.blocked` and deliberately does not invent the
    `DRAIN_BLOCKED` condition (it cannot date one). Consumers reading the *field*
    see the new truth at once; consumers reading the *condition* see the old one
    until the next reconcile pass. Enumerate which side each consumer is on and
    check the direction the disagreement errs — here it over-states brokenness for
    one pass, which is the safe way round, but nothing in the design made that so.
42. **A spec-level property that shares a name with the container-label one is a
    trap at zero call sites.** `ServerSpec.holdsWorldData` (desired state, added in
    round 11) versus `WorkloadContract.holdsWorldData` (read off the running
    container's `Labels.WORLD_DATA`). The drain must use the second; reaching for
    the first after a `persistent → ephemeral` edit is a stop with no save (item
    5). Its KDoc says so, which is not enforcement — the safe shape is a test that
    asserts the spec property has no reference in `core/src/main` until the
    workload builder genuinely needs it.

43. **A flag's scope is set by which adapter overload happens to exist, not by
    its rule.** `escalates(startedAt, failureClass, now, after)` contains nothing
    about drains — PERMANENT fires at once, RETRYABLE after a threshold — but the
    only adapter is `DrainStatus.escalated()`, and `deriveConditions` calls only
    the adapter. So `NEEDS_ATTENTION` is a *drain* flag purely because
    `FailureStatus.escalated()` was never written. Six of the seven sites that
    record a `PERMANENT` `status.failure` (`rejectDefinition`, `refusePlacement`
    on `PINNED_NODE_UNKNOWN`, `converge`'s EXITED, `awaitJoinable`'s
    non-retryable probe, `forbiddenTransition`, `nodeFailure`) raise nothing,
    and `isBlockedByPermanentFailure` then freezes each status for ever. Whenever
    a rule is factored into a general predicate plus one typed adapter, enumerate
    the types that *could* be adapted and check whether their absence was decided
    or defaulted.
44. **A badge that reads "healthy" is worse than one that reads "on its way
    out".** Round 8 flagged `TERMINATING` on a permanently failed drain as the
    wrong answer that matters. `Reconciler.forbiddenTransition` is the mirror:
    `PERMANENT` `DRAIN_STALLED`, `drain = null`, `phase = RUNNING`, so
    `displayState` renders `RUNNING` on a server the loop has permanently stopped
    managing. `TERMINATING` at least makes somebody look. For every path that
    records a permanent failure, ask what badge it produces and whether that badge
    invites a second look or ends one. `PENDING` (`refusePlacement`) and `STOPPED`
    (`CONTAINER_EXITED`) fail the same test — both read as states you wait out.
45. **A threshold pushed to the client is a threshold deleted.** When `:core`
    declines to fold a fact into a condition, `:api` re-derives it (`passFailure`)
    and `API.md` then tells the dashboard to derive it a third time in TypeScript
    — `server.status?.failure ? 'not progressing'` — with **no** threshold at all.
    So the alarm-fatigue objection that kept the fact out of the flag reappears in
    the one place with no `drainAttentionAfter` and no audit. Item 39 counts
    derivations inside the repo; count the ones the API doc prescribes too, and
    treat a documented client-side re-derivation as evidence the fact belongs in
    the condition.
46. **The escalation's only non-dashboard channel is a log line nobody asserts
    on.** `DrainController.abort`'s permanent branch passes `occupancy != null`
    and `failure.attempts` in the order the format string wants `attempts` and
    `answeringPlayers` — so the `LOG.error` an operator's alerting greps reads
    "stopped permanently after true attempt(s) ... answeringPlayers=1". The
    retryable branch beside it is correct, which is why review slides past it.
    Structured-logging arg order is untyped and untested; read every escalation
    log line against its own format string, placeholder by placeholder.

## Round 12: the proxy control surface

43. **A "start-or-join" whose join condition is `not finished` needs a way for a
    sweep to *become* finished that does not depend on the sweep.**
    `ControlService.startOrJoin` publishes a `TransferOperation` with
    `requested = players.size` and then issues N requests; `finish()` is reached
    only from a `whenComplete` when `settled >= requested`. Any throw part-way
    through the issuing loop — or any future that never completes — leaves an
    operation permanently unfinished, and every later POST *joins* it instead of
    retrying, for ever. The remedy the drain protocol asks for (retry step 4) is
    the thing the join branch suppresses. Whenever a counter-based completion test
    is fed by a loop that can exit early, ask what publishes the denominator and
    whether the numerator can still reach it.
44. **A bundled level-triggered assert refuses in halves.** `PUT /v1/backends/{name}`
    asserts registration *and* seal; the `ADDRESS_CONFLICT` throw happens inside
    the registry lock and `assertAdmission` is the line after it, so refusing the
    address half silently refuses the seal half. The refusal is right; dropping the
    seal with it is not, because the seal has nothing to do with the address. When
    two independent facts share one endpoint, check that a refusal of one is not a
    refusal of the other — especially when one of them is a drain step.
45. **A leak is only counted on the path somebody remembered to count.** The seal's
    honesty argument rests on `admittedWithoutAlternative`. It is incremented in
    exactly one of the three ways a player can end up on a sealed backend:
    `InitialChoice.AdmitAnyway`. The `Redirect` whose alternative vanished between
    listing and `getServer`, and `SealPolicy.onServerSwitch`'s `Allow` for a player
    with no current server (login and Velocity's own fallback reconnect), both let
    a player through silently. Enumerate the leak paths from the *player's* side,
    not from the enum's.
46. **Soft state with no incarnation marker cannot be verified by reading it back.**
    The seal is deliberately unpersisted so a dead orchestrator cannot leave a
    backend sealed for ever — sound. But `:core`'s stated mitigation is "read it
    back rather than assume the write stuck", and a read that follows the re-assert
    returns `true` whether the seal held for an hour or was lifted by a restart
    200ms ago. Level-triggered soft state needs the holder to publish an
    incarnation id or start timestamp, or the consumer's evidence chain has a hole
    exactly the width of one restart. Same family as item 12.
47. **A compensating update placed after the lock is released.**
    `ControlService.deregister` drops the seal (`admission.forget`) *outside* the
    `registryLock`, with a comment asserting an ordering the code does not enforce:
    a concurrent `PUT` can re-register and seal in the gap and have its seal
    erased. Cheap to fix, and the shape recurs — whenever a comment says "only
    after X", check that X and the thing after it are under the same lock.

## Round 13: the widened escalation

48. **When two escalation arms can both fire and one wins the message, audit the
    2x2 of failure classes, not the 2 arms.** `NEEDS_ATTENTION` derives from a
    drain arm and a pass arm, and the drain arm is checked first. Three of the
    four class combinations are fine; the fourth is not. A *retryable* drain
    failure beside a *permanent* pass failure renders the drain arm's "The loop
    keeps retrying and the container keeps running", while
    `isBlockedByPermanentFailure` gates the server out on the very next pass. The
    alerting channel is the condition message, so the operator is told to wait by
    the one surface an alert quotes. Whenever an arm wins by position rather than
    by severity, work out which cell of the class matrix makes the winner's prose
    false.
49. **"Fixed at the class, not the instance" usually means relocated.** Wrapping a
    positional `LOG.error(fmt, vararg Any?)` in a helper whose parameters are all
    distinct types does stop *callers* swapping arguments — genuinely, and the
    compiler proves it. It does not stop the helper body's own positional mapping
    from being wrong, and it does nothing for every other multi-placeholder log in
    the module. Test the claim by asking: which log line would be read first after
    a world was lost? Here that is `DrainController`'s stop line, which still
    passes `drain.worldSaved` and `contract.holdsWorldData` — two adjacent
    `Boolean`s — positionally. The fix went to the line that was found broken, not
    to the line whose breakage costs a world.
50. **A new derivation that deliberately picks a different time anchor from the
    existing one has just documented a defect in the existing one.** The pass arm
    anchors on `failure.occurredAt` precisely so a four-hour block does not make
    the first retryable hiccup fire instantly. `DrainStatus.escalated()` still
    anchors on `drain.startedAt` and does exactly that. Correct not to move it
    mid-change, but "the old arm keeps the anchor we just argued is wrong" has to
    leave the review as a routed follow-up, not as a comment.
51. **An end-to-end conformance test pins the direction it was written for.** The
    `status.failure == drain.failure` identity has three dependents (`:core`
    wording, `:api` `detail()` precedence, `:store` V5's drop). `AttentionTest`
    pins the `:core` one; `DisplayConformanceTest` drives `:api` end to end for
    the *pass-failure* direction only, and `:api`'s own unit test builds the
    identity into its fixture, so it cannot observe the identity being broken
    upstream. One red test is enough to stop a change, so the pin works — but when
    a fact becomes load-bearing in a third place, check which dependents are
    covered by a test that could actually see it break.

## Round 13: steps 2, 4 and 6 with bodies

52. **A limit that counts passes while its KDoc says it counts sweeps.**
    `MAX_TRANSFER_ATTEMPTS = 6` is documented as "how many transfer *sweeps* step 4
    asks for", but `awaitEvacuated` calls `issueTransfer` on **every pass** with
    players online and the plugin's start-or-join makes each of those the *same*
    sweep. So the counter measures passes: 6 of them at the 2 s `POLL` is ~12 s,
    against a `playerTransferTimeout` of 120 s that can therefore never be
    reached. Whenever an orchestrator-side counter bounds a side effect the
    counterparty deduplicates, work out what one increment actually costs the
    counterparty — if the answer is "nothing", the counter is a wall clock in
    disguise and its units are the poll interval.
53. **A limit checked above the "is there anything left to do" test converts a
    bound into a permanent wedge.** `issueTransfer` asks `exhausted` before
    anything looks at the player count, and `startTransfer` reaches it with no
    emptiness check at all, so once `transferAttempts >= MAX` the drain aborts on
    every pass **including passes where the server is empty**. Nothing ever resets
    the counter (`teardown` clears `drain`, and teardown is downstream of the
    thing that is blocked), so a delete never completes. A retry limit is only
    safe if the *success* path is still reachable after it trips: put the limit
    below the precondition it is bounding, never above it.
54. **Two derivations of the same third-party identity, one of which is only
    defined after readiness.** The proxy is told a backend's address twice —
    `ProxyFleet.linkFor` builds `"${node.name}:${port}"`, `ProxyPass.backends`
    builds `"${status?.endpoint?.address ?: serverName}:${port}"`. They agree only
    once `awaitJoinable` has written an endpoint. Register during the
    CREATING/STARTING window (or in the window after a teardown clears
    `endpoint`) and every later `PUT` is `ADDRESS_CONFLICT` — which the protocol
    deliberately makes non-recoverable without a `DELETE`, so drain step 2 aborts
    for ever and the backend is undeletable. Whenever a value is sent to an
    external registry that refuses changes, prove there is exactly one expression
    that computes it, and that the expression is total over the workload's whole
    lifecycle rather than over its healthy state.
55. **A level trigger re-asserts what a one-shot step deliberately stopped
    asserting.** `holdSeal` skips once `deregisteredAt != null`, with an explicit
    comment that asserting after step 6 would put the backend back in the routing
    table moments before the stop. The proxy's own `assertBackends` sweep then
    does exactly that, because `DrainState.DEREGISTERED.sealsBackend()` is true so
    the backend is still in `matched` and gets a `PUT`. Sealed, so nothing is
    routed there — but it restores the registry entry that Velocity's fallback
    reconnect (`SwitchDecision.AllowSealed`) can land a player on. When one
    component opts out of a level trigger for an ordering reason, check every
    *other* holder of that trigger for the same opt-out.
56. **A measurement assertion whose counter is structurally zero on the path under
    test.** `a drain whose transfer keeps failing backs off instead of spinning`
    measures `plugin.sweepsStarted.size shouldBeLessThanOrEqual 6` on a scenario
    where every transfer is refused `DESTINATION_UNKNOWN` *before* the fake
    records anything — so both `sweepsStarted` and `transfers` are 0 and the
    headline measurement is vacuous. Only `failure.attempts > 1` bites. Before
    accepting "it is measured rather than reasoned about", find the line in the
    fake that increments the counter and check the scenario reaches it.
57. **Compensation added at the site that was found broken, not at the class of
    sites.** `stop()` now catches `NodeException` so the abort can re-register the
    backend. `awaitStopped`'s re-issue — the *other* `stopWorkload` call, reached
    in `STOPPING`, which is a sealing state — does not, so an escape there skips
    `restoreRegistration` and leaves a running backend sealed and deregistered
    with `Reconciler.nodeFailure` writing no drain change. Same family as item 49.
    Also: the class KDoc's single-point claim ("no path reaches `stopWorkload`
    except through `requireEmpty` followed by `mayStop`") is false for this site —
    it uses an inline `online > 0` check and lets an *unanswered* probe through.
    Deliberate and safe, but the sentence that is now the whole safety argument
    does not describe the code.

## Round 14: the anchor is the new counter

58. **Deleting a counter in favour of a clock moves the whole bound onto one
    nullable field, and that field's stamp site is now the safety property.**
    `MAX_TRANSFER_ATTEMPTS` is gone and `exhausted` is `now - sealRequestedAt >
    playerTransferTimeout + 2s x online`. A counter starts at 0 by construction; a
    clock needs an anchor, and `sealRequestedAt` is written at exactly one place —
    the `DRAIN_REQUESTED -> SEALED` edge, *below* `holdSeal`. Nothing re-enters
    `DRAIN_REQUESTED` (the resume ladder tops out at `SEALED`), so any drain whose
    first bodied pass could not seal, or ran with `subject.seal == null`, carries a
    null anchor **for its whole life** and silently falls back to `enteredStateAt`
    — the restamping anchor the change was made to remove. When a bound converts
    from count to duration, enumerate every path that reaches the bound *without*
    passing the stamp, and treat a `?: fallbackAnchor` as an admission that one
    exists.
59. **An anchor chosen for immutability is not automatically an anchor for the
    right quantity.** `sealRequestedAt` cannot be restamped, which is why it was
    picked; it also starts at step 2, so steps 2 and 3 — a `NoCapacity` wait, a
    flaky `holdSeal`, an orchestrator that was down — spend a budget the schema
    documents as step 4's (`DrainSpec.playerTransferTimeout`, "how long step 4
    gets"). Past the allowance, step 4 aborts on its first pass having asked
    nobody to move, and no path resets the anchor. Fixing a restamping bug by
    moving the anchor *earlier* trades "the bound never terminates" for "the bound
    is never granted"; the shape that gets both is a **set-once stamp on the first
    entry into the state being bounded** (`x = x ?: now`), never a reused stamp
    from an earlier step.
60. **Item 34 reopens through whichever state most recently gained a body.**
    `resumeInto` clears `failure` whenever the state it resumes into does not
    itself land in `DRAIN_FAILED`. That was safe while `SEALED` was a no-op. Now
    `secureDestination` has a body and reports `Progressed`, so the cycle
    `DRAIN_FAILED -> SEALED (Chosen, Progressed, failure cleared) -> TARGET_RESOLVED
    (abort)` runs for ever with `attempts` pinned at 1 and `occurredAt` restamped
    every other pass — `escalates()` never fires, `queue.succeeded` resets the
    backoff, and a status is written every pass. Reached whenever `destination` is
    null at resume time, which `TransferReport.DestinationLost` guarantees. Re-run
    item 8/34 against **every** state that gains a body, not only the one the
    change was about.
61. **A garbage collector built on a tolerant list read must consult the
    tolerance, not just enjoy it.** `assertBackends` computes
    `wanted = matched.map { it.server }` from `store.listAll().servers` and
    `DELETE`s every proxy registration not in it. `listAll` was chosen over
    `listServers` precisely so one bad row cannot fail the sweep — but
    `ServerListing.unreadable` is discarded, so an undecodable *definition* row
    turns into an active deregistration of a live backend. `UnreadableServer.name`
    exists for exactly this caller and its KDoc says so ("anything that treats
    'not in the list' as 'purged' — a garbage collector — would otherwise report a
    deletion that never happened"). Whenever a read is made tolerant, check whether
    any consumer derives *absence*; tolerance without the exclusion list is
    strictly worse than the strict read for those consumers.

## Round 15: the flag that closed one site

62. **A `derivedOnly`-style marker closes the resume's failure-clearing hole only
    at the sites it is attached to, and the class is bigger than the site that was
    found.** `resumeInto` clears `failure`/`blocked` on any resumed step that did
    not itself land in `DRAIN_FAILED`; the round-14 fix marked
    `secureDestination`'s `Chosen` and nothing else. `save()` has **two** early
    returns of exactly the same shape — `!contract.holdsWorldData` and
    `saveIsCurrent` — that report `Progressed` after reading a container label or
    a stored timestamp and issuing no RPC. So an *ephemeral* workload whose step 6
    or step 7 keeps failing alternates `SAVING → DEREGISTERED` (Progressed,
    failure cleared) with `DEREGISTERED → abort` at `stepInterval` for ever:
    `attempts` pinned at 1, `occurredAt` restamped, `escalates()` never true,
    `queue.succeeded` resetting the backoff on the `Progressed`. Whenever a
    "this step did no work" marker is introduced, enumerate **every** state body
    that can return without touching the node or the proxy — a label read, a
    timestamp comparison, a `?:` — rather than marking the one the bug was found
    in.
63. **The persistent variant of the same loop runs at the evidence-gap cadence
    instead of the poll interval, and that is enough to delete the escalation.**
    With `holdsWorldData` true the resume's `SAVING` does real work, so
    `Progressed` there is honest — but `dropUnusableSaveEvidence` voids the
    confirmation every `saveEvidenceMaxGap` (30 s), which sends the ladder back
    through `SAVING`, which clears the failure. `attempts` therefore resets every
    30 s, `FailureStatus.occurredAt` restamps, and a 15-minute
    `drainAttentionAfter` can never elapse. A drain stuck for hours on a refused
    `stopWorkload` never raises `NEEDS_ATTENTION` and re-issues `save-all flush`
    every 30 s. Before believing any "the backoff grows and it escalates"
    argument, find the *slowest* thing that periodically produces a `Progressed`
    on the failing cycle and compare its period against the attention threshold.
64. **A non-null parameter is only "no fallback" if the caller cannot supply
    one.** `exhausted(pass, transferStartedAt: Instant)` is documented as
    unfallbackable — "A `?:` fallback here is what made both defects silent, so
    there is none" — and the single caller opens with
    `val anchor = drain.transferStartedAt ?: now`. Dead today, because
    `transferStep` stamps immediately above. It is exactly the elvis the design
    forbids, moved one frame up where the callee's signature cannot see it, and it
    restores defect 1 (a restamping anchor, so the bound never trips) for any
    future second caller of `issueTransfer`. When a type change is offered as the
    enforcement, read the call site, not the signature.
65. **A step that is excused from clearing the failure is also excused from
    clearing the block, and nothing downstream clears either.** `resumeInto`'s
    `derivedOnly` branch returns before `copy(failure = null, blocked = null)`, and
    no ordinary forward step clears them. So a drain that blocked once, resumed via
    the derived step, and then succeeded carries a stale `DrainBlock` — and a drain
    that hit one transient `DestinationLost` carries a stale `FailureStatus` —
    through the transfer, the save, the deregistration and the stop. `:api` then
    renders "waiting, not stuck" or "the drain aborted; the server is still
    running" about a drain seconds from `stopWorkload`. Whenever a clearing rule
    grows an exemption, ask what else the exempted branch was the *only* thing
    clearing.

## Round 16: the hysteresis, and what a pass's own duration costs

66. **A pass stamps its evidence at its own start, so the pass's *duration* is
    charged to the evidence gap — and a save is the longest thing a pass does.**
    `advance` takes one `now` at entry; `PlayerOccupancy(…, now)` and
    `worldSavedAt = now` are both that instant, while `requestSave` runs after.
    The next pass computes `now - lastProbedAt` (= the previous pass's *start*)
    and voids the confirmation when it exceeds `saveEvidenceMaxGap` (30 s, a
    `ReconcilerConfig` constant). So any server whose `save-all flush` takes
    longer than ~30 s can never keep a confirmation: `SAVING` saves, `DEREGISTERED`
    finds the evidence void and returns to `SAVING`, for ever, `Progressed` every
    pass, no failure recorded, `NEEDS_ATTENTION` never raised, container never
    stopped, delete never completes. The schema's own default `saveTimeout` is
    **180 s** and `stopGracePeriod` defaults to 240 s, so a save far longer than
    the gap is anticipated everywhere except here, and nothing cross-checks the
    two — they live in different modules and one is per-server while the other is
    per-loop. Structurally invisible to the suite: `FakeNode` never advances the
    test clock inside an exec, so no test can produce a pass longer than a pass.
    Whenever a freshness bound is compared against a timestamp, ask *when in the
    pass that timestamp was taken* and what the pass does after taking it.
67. **A `PERMANENT` failure that survives onto a drain no longer in
    `DRAIN_FAILED` is a permanent freeze, because the gate is keyed on the class
    and not on the state.** `Reconciler.Pass.isBlockedByPermanentFailure` returns
    `Failed` with no status write for any non-terminating server whose *status*
    failure is `PERMANENT` at the observed generation, and `status.failure` is a
    copy of `drain.failure`. That was harmless while `resumeInto` cleared the
    failure on every resume that did not re-abort: the operator's edit bumped the
    generation, the gate opened for one pass, the resume cleared the failure and
    the drain carried on. Round 15 (`derivedOnly`) and round 16 (`settleRecords`'s
    `resuming` rule) each widened the set of resumes that *keep* the failure, so
    the edit now buys exactly one step and the pass after it re-freezes — with
    the drain parked in whatever state that step reached. The sharpest instance is
    a `NodeException.Rejected` on `stopWorkload`: the recovery pass issues the
    stop, the container goes down, and the loop is gated out before it can ever
    run `awaitStopped` or `teardown`. Whenever a rule is added that *withholds*
    clearing a failure, enumerate every consumer that treats the presence of that
    failure as a reason to stop reconciling.
68. **A retry that did not achieve its object may not claim it did work.**
    `awaitStopped`'s re-issue branch sets `workDone = true` because the
    `stopWorkload` RPC returned — but the branch is only reached *because* the
    container is still running, i.e. the previous stop did not take. Under the
    round-16 rule that claim deletes a recorded failure, so a `STOPPING` loop
    against a container that will not die alternates "abort on a node blip"
    with "re-issue and wipe the failure", pinning `attempts` at 1 for ever
    (item 11 again, in the one state where the container is supposed to be going
    away). The flag's own definition is "a request that left this process **and
    came back with what it needed**"; a stop that came back and left the
    container running did not.

## Round 17: the re-probe that closed one hole and opened another

69. **A re-probe added to make a timestamp honest becomes a new reader of the
    player count, and a reader of that count that does not void the confirmation
    is a stop authorised over an observed play session.** `save()`'s `Confirmed`
    branch re-pings after `save-all flush` returns and stamps the occupancy from
    it. `requireEmpty`'s KDoc maintains a *count* of the sites that read a
    positive count and void the evidence — `requireEmpty`, `transferStep`,
    `awaitStopped` — and the re-probe is a fourth that reads one and voids
    nothing. Same family as item 14 (check every call site against the
    justification, not the name), arriving from the opposite direction: not a new
    caller of the voider, a new *reader of the trigger* with no call to it. When a
    step gains a second probe, ask what the first probe's branch does with
    `online > 0` and whether the second one does it too.
70. **The two halves of the save-evidence rule are carried by fields whose names
    say the opposite, and only one of them is load-bearing.**
    `saveIsCurrent` returns on `!confirmed.isBefore(containerStartedAt)` whenever
    the runtime reports a start time — which is every running container — and
    never consults the age, so `worldSavedAt` carries *no* freshness. The whole
    freshness half of `dropUnusableSaveEvidence` is `watched`, i.e.
    `now - lastProbedAt <= maxGap`, i.e. the **occupancy** instant. And
    `lastProbedAt` advances on any probe that *answered*, whatever it counted. So
    "the chain of zero-player observations is unbroken" is implemented as "some
    probe answered recently" plus "each positive count voided at its own call
    site" — a distributed invariant with no single enforcement point. Before
    trusting any rule that names `worldSavedAt`, check whether the property is
    actually on `PlayerOccupancy.observedAt`.
71. **A cycle bound must not reuse a freshness constant, because one honest cycle
    contains the thing the freshness constant is shorter than.**
    `goingRoundInCircles` aborts when `now - resaveForcedAt > evidenceGap` (30 s),
    but one legitimate lap is *goingRoundInCircles → SAVING → save → DEREGISTERED
    → stop*, and the save alone is bounded by `spec.lifecycle.drain.saveTimeout`
    (180 s by default). So any server whose save outruns 30 s aborts on its second
    genuine forced re-save with a message asserting a defect ("it does not clear
    on its own") that then clears on its own two passes later. Exactly round 16's
    critical 1 in a new place: a per-loop 30 s constant applied to a per-server
    quantity floored by a 180 s one. The two live in different modules and nothing
    cross-checks them.
72. **A `NonCancellable` justified by a consequence its own branch cannot
    produce.** The shield around `save()`'s re-probe is argued as "a cancelled
    pass loses the confirmation, the next pass reads `saveRequestedAt != null`
    with no `worldSavedAt`, aborts `PERMANENT` and wedges the drain". Provably
    impossible there: `save()` returns `abort(PERMANENT)` at the
    `saveRequestedAt != null` check *above*, so the field is always null on the
    `Confirmed` path. The real cost of losing the record is one repeated
    idempotent flush — the benign repeat ruled acceptable at round 7 — and the
    real benefit is invariant 5. The trade still lands (the region is bounded by
    a gRPC `withDeadlineAfter` of 10 s), but a shield whose stated reason is
    wrong will be widened next time by the same reasoning. Read the guard clauses
    above a branch before believing what a comment says that branch can leave
    behind.
73. **"That narrowing cannot fire" has to be checked branch by branch, and the
    file's own KDoc is not evidence.** `settleRecords` declines to clear `blocked`
    only on `workDone` because "every branch of `secureDestination` and
    `transferStep` that leaves `DRAIN_FAILED` claims `workDone`".
    `DestinationChoice.Chosen` is documented eight hundred lines away as
    "**Re-derived, not done**, so `workDone` is left false" and is exactly the
    branch a proxied drain resumes into from a block with `destination == null`.
    The conclusion survives; the argument does not. A "the rule would change
    nothing" claim is a claim about a set of branches — enumerate them.
74. **Adding an abort to a `Progressed` cycle reports it without pacing it.**
    Item 8/34, reopened by construction: the new lap is
    *abort → `Retry`* alternating with *resume → save → `Progressed`*, and
    `ReconcileLoop.requeue` calls `queue.succeeded` on `Progressed`, so the
    attempt counter the backoff reads is cleared every other pass and the flush
    rate stays at `stepInterval`. The escalation still fires, because it is
    anchored on `resaveForcedAt`/`firstOccurrenceOf` rather than on `attempts`.
    Whenever a new abort is placed on a cycle whose *resume* does real work, state
    separately what it does to the report and what it does to the rate — they are
    now decided by different fields.

## Round 18: the enforcement point catches readers, and the hole is in a non-reader

75. **A "reading the count means voiding" enforcement point cannot see the branch
    that reads no count but acts on the drain in the same pass.** `readPlayers`
    makes `PlayerReading.Occupied` carry an already-voided drain, which closes
    every branch that *reads* `probe.online`. `advance` still takes
    `readPlayers(...).occupancy` and throws the drain away, so `step` runs against
    an unvoided record — and at `DEREGISTERED` the first thing `step` does is
    `holdSeal`, **before** `requireEmpty`. A proxy control blip therefore aborts
    with `worldSavedAt` intact and `pass.occupancy` positive, and
    `Reconciler` writes that occupancy into `status.players`, so the very
    observation that should have destroyed the confirmation *refreshes* the
    evidence window instead (item 70). When auditing an enforcement point, do not
    stop at "who reads the trigger" — enumerate everything that runs against the
    unenforced value *before* the enforcing call, per state.
76. **A per-state exemption is safe only as long as the state's own invariants
    hold, and those are what change.** The `advance` exemption is defensible for
    `SEALED`/`TARGET_RESOLVED`/`TRANSFERRING`/`SAVING` because
    `dropUnusableSaveEvidence` plus the resume ladder make `worldSavedAt`
    provably null in all four. Nothing states that, nothing tests it, and the
    call-site comment argues something else entirely (resume-ladder behaviour).
    An exemption whose written reason is not its actual safety argument will be
    widened by the next reader on the written one.
77. **"Unobservable in this harness" is usually a claim about the default
    fixture.** `confirmedAt` vs `observedAt` was left unpinned because
    "`FakeNode`'s probe consumes no clock time" — but `FakeNode.exec` routes
    `mc-monitor` through the same `onExec` hook that tests already use to make
    `saveAll()` take sixty seconds, so `harness.clock.advance()` inside the probe
    pins it directly. Same class as the round-17 finding that a 60 s player
    session was being protected by `dropUnusableSaveEvidence` rather than by the
    branch under test: a statement about what the fixture does today, mistaken for
    a statement about what the harness can express.
78. **A grace period is not a save budget, and only one kind has an invariant.**
    `SpecInvariants.stopGraceProblem` ties `stopGracePeriod` to `saveTimeout` for
    `PaperServer` only; `ProxyLifecycleSpec` documents in prose that it has no
    such rule. So `DrainSubject.stopGracePeriod` satisfies "exceeds `saveTimeout`"
    for one implementation and not the other, and any bound derived from it
    inherits that split. It is also operator-settable up to
    `MAX_STOP_GRACE_PERIOD` (2 h), so a bound sized from it can silently become
    thirty times the quantity it was meant to cover.

## Round 19: two enforcement points that agree, and the pin that covers neither

79. **Two guards for one rule, jointly pinned and individually unpinned, is a
    guard with a one-line half-life.** The pass-entry adoption
    (`advance`/`advanceOnce`'s `if (reading is Occupied) drain.unconfirmWorldSave()`)
    and the exit net (`DrainProgress.dropSaveContradictedByPlayers`, asserted on
    the single return) both close round 18's critical, and *either alone* keeps
    the whole suite green. The implementer's own sabotage found this. The
    distinction is real and correctly stated — the net repairs what is
    **written**, the adoption governs what is **decided**, and no repair of a
    record can un-stop a container — but "today nothing decides to stop before the
    gate" is an enumeration, and the same enumeration was wrong in rounds 17 and
    18. Consequence: a future reader who profiles the pair will find one deletable
    with 765 green tests, and only a docstring stands in the way. When you accept
    a defence-in-depth pair, ask which half a green suite would let somebody
    delete, and whether the *surviving* half is the one that governs the decision.
    The net in particular is unreachable by construction, so it can never be
    scenario-tested; its wiring into the single exit needs a structural pin
    (the repo has the precedent: `TransferNeverKicksTest` greps module sources).
80. **An invariant asserted over the value a function returns is not asserted over
    the value the caller writes.** `dropSaveContradictedByPlayers` is keyed on
    `DrainProgress.occupancy`, and its KDoc justifies itself by "`Reconciler`
    writes them onto one observed status side by side". `Reconciler` actually
    writes `players = progress.occupancy ?: pass.previous?.players`, so on any
    pass whose probe did not answer the recorded pair is *carried-forward count*
    beside *this pass's drain*, which the net never sees. Unreachable today only
    because a confirmation can only be minted under a fresh zero reading. Whenever
    a rule is moved to "the point the pass is recorded", check that the point
    chosen is the one that produces the fields actually persisted, not the one
    that produces the object handed to the persister.
81. **The one function that consults the save confirmation before any zero-player
    gate is `resumeInto`'s ladder, and for a proxied subject the resume is
    ungated.** `resume(pass, drain, gated = subject.router == null)` — with a
    router, `resumeInto` runs with no `requireEmpty` above it and reads
    `saveIsCurrent` to choose the re-entry state. It cannot reach a stop, because
    every state it can land in re-gates (`DEREGISTERED` → `holdSeal` →
    `requireEmpty`), but it is the concrete answer to "what decides on the
    confirmation before the gate", and it is the thing to re-check whenever a
    state's body changes. The class KDoc's "no path reaches `stopWorkload` except
    through `requireEmpty` followed by `mayStop`" (item 57, open since round 13)
    remains false for `awaitStopped`, which gates on an inline `readPlayers` and
    deliberately lets an *unanswered* probe through — and that sentence now sits
    beside a second single-point claim added in round 19.
82. **A per-subject quantity replacing a per-loop one closes the overstatement at
    the sites that were edited and leaves it at the site that stops the
    container.** Round 19 correctly re-scoped "the schema guarantees the grace
    period exceeds the save timeout" to `PaperServer` in `DrainSubject.kt` and
    `Node.kt`, and introduced `DrainSubject.saveTimeout` (one reader:
    `goingRoundInCircles`). `DrainController.stop`'s own KDoc — the KDoc on **the
    only container stop in this codebase** — still states it unqualified, as does
    `LocalNode`'s non-positive-grace error message. When a claim is re-scoped,
    grep the claim's *words*, not the files the change touched.
83. **A bound that shrinks is still a bound that can misdiagnose, and the
    anchor's clearing rule decides how badly.** `goingRoundInCircles` moved from
    `evidenceGap + stopGracePeriod` to `evidenceGap + saveTimeout` — strictly
    smaller for every `PaperServer`, since the schema forces
    `stopGracePeriod >= saveTimeout + MIN_STOP_GRACE_MARGIN`. Safe (report-only,
    `RETRYABLE`, container untouched) but the false-positive rate goes *up*, and
    `resaveForcedAt` is cumulative: it is stamped once and cleared only by
    `forgetSaveEvidence`, i.e. only by an observed player. So `circling` can be
    hours on a drain whose first lap cleared normally, and the message reports
    that number as "has not cleared on its own in Xs". Whenever a duration bound
    is retuned, check what resets its anchor and whether the success path does.

## Round 20: the structural pin, and what a shape-based assertion cannot see

84. **A "single exit" pin written as `trim().startsWith("return ")` cannot see a
    return that is not the first token of a line.** `DrainWiringTest`'s first
    assertion filters `advance`'s code lines for that prefix and requires the list
    to be exactly `["return <bound name>"]`. Two ordinary Kotlin shapes escape it
    entirely — `if (cond) return progress` and `val x = progress.occupancy ?: return
    progress` — and each is a second exit from the one function whose whole job is
    that every `DrainProgress` leaves through `dropSaveContradictedByPlayers`.
    Verified by replicating the test's own regexes against mutated sources: both
    mutations keep all four assertions green while the controls (deleting the rule,
    deleting the adoption) go red. A structural pin's red-proof only demonstrates
    the mutations somebody thought to try; enumerate the *language forms* of the
    thing being forbidden, not the one the current code happens to use.
85. **Following a bound name pins which value flows, never the condition under
    which it was computed.** The same test binds the rule's result with
    `val (\w+) =` and then asserts `return <name>`; the adoption test binds
    `observed` from the `PlayerReading.Occupied` line and asserts
    `step(pass, observed)`. Rewriting either right-hand side as
    `if (<extra conjunct>) <the safe call> else <the unvoided value>` keeps the name,
    keeps the flow, and deletes the guarantee. Both mutations verified green.
    Regex-following-a-name is the right technique against renames and rewraps; it
    is no technique at all against a narrowed predicate, so the predicate still
    needs a behavioural test or a shape assertion of its own.
86. **A single-file source scan pinning a claim whose scope is "this codebase".**
    `the container stop has exactly two call sites` scans only
    `DrainController.kt`, while the KDoc it replaces says `Node.stopWorkload` is
    called twice *in this codebase*. True today (the only other references are the
    `Node` declaration, the `LocalNode` override and an integration-test fixture) —
    but a stop added to `Reconciler.teardown`, to a future rescheduling path or to a
    node-drain helper leaves every assertion green, and those are exactly the paths
    invariant 1 exists for. The precedent it cites, `TransferNeverKicksTest`, scans
    its whole module. A count pinned in the file it was written in is a pin on the
    file, not on the codebase.
87. **A count pin is a notification, not an enforcement of the gate it is named
    after.** `one behind each gate` asserts *where* the two stops are, and nothing
    about `mayStop` or `readPlayers` being above them. When a third legitimate site
    appears the repair the test invites is "bump the number and add a range", which
    is precisely the maintained-count-of-call-sites failure it was built to retire,
    relocated from a KDoc into a test. Cheap strengthening: assert each
    stop-bearing function's own range also contains the gate call.

## Round 21: the harness becomes the instrument, and presence is not content

88. **A mutation harness whose verdict is per test *class* reports "caught" for
    any red, including one it did not cause.** `scripts/dev/drain-wiring-mutations.sh`
    closes the two obvious false-greens — a `sed` that matches nothing is an
    exact-count-1 check in Python and exits non-zero, and a mutation that does not
    compile leaves no JUnit XML and is scored UNKNOWN — but the caught/not-caught
    decision is `grep -q "<failure" TEST-<class>.xml`. The `awk` that names the
    reddened testcase is printed for a human and is not part of the verdict. So any
    pre-existing red in `DrainWiringTest` (rename `advance` and `rangeOf`'s
    `check(hits.size == 1)` throws; run from the wrong working directory and the
    companion-object `check` throws) scores all seven `$WIRING` mutations *and* both
    `$WIRING` controls as caught, and the script exits 0 having proved nothing. The
    controls cannot detect it, because the controls fail the same way. Fix: carry
    the expected testcase name in the mutation tuple and require it in the red set
    the `awk` already extracts. General rule: a red-proof must name *which*
    assertion had to bite, or its controls are testing the harness's plumbing rather
    than the assertions.
89. **Upgrading a rule pin from presence to content leaves the *gate* pins at
    presence, and that is where the round-20 defect class relocates.** Round 21
    correctly moved the rule assertions to behaviour (`SaveEvidenceTest` on
    `adoptSaveClause` and `dropSaveContradictedByPlayers`) and left
    `DrainWiringTest` asserting only "applied unconditionally". But the gate
    assertion — `codeIn(gate.body).any { codeOf(it).contains("mayStop(") }` — is
    still a *presence* test, which is exactly the shape mutations D3/D4 defeated for
    the rules. `if (!drain.mayStop(contract, observation.startedAt, now, evidenceGap)
    && !drain.playersEvacuated)` at `DrainController.awaitStopped` keeps the token,
    keeps the count at two, keeps the enclosing-function set at `{stop,
    awaitStopped}` — and deletes the guarantee. Nothing else covers it: the branch's
    detail string, `"the stop is not re-issued until the world is saved again"`,
    appears **nowhere in the test suite**, so no scenario reaches it. D7 (delete the
    gate) is caught; D7-narrowed is not, and the D-set does not contain it. When a
    pin is strengthened from shape to behaviour, enumerate the *other* assertions in
    the same file that are still shape-only and ask which of them a narrowing would
    walk through.
90. **A source classifier that exempts by method *name* is bought off by a
    same-named wrapper.** `the container stop is called from one file in this module`
    partitions files mentioning `stopWorkload(` on whether they also mention
    `fun stopWorkload(` — declarer/overrider versus caller. The reasoning is right
    (a distributed `Node` adds an override and passes untouched; a maintained file
    list would be a maintained lie) and the classification is the correct
    generalisation, but the predicate is a string. A private wrapper —
    `private suspend fun stopWorkload(handle, grace) = node.stopWorkload(handle, grace)`
    added to `Reconciler.kt`, which is exactly what somebody writes when they need a
    stop in two places — puts that file on the *performing* side and the caller
    assertion never sees it. The harness's D6 appends a function that calls the stop
    directly, so it does not cover the shape. Cheap repair: classify the *call*, not
    the file — a call whose `enclosing()` function is that file's own
    `stopWorkload` override is performing, any other call is a decision point.
91. **The stop-scan's alphabet is one method, and the class of container-ending
    operations is three.** The pin is keyed on `stopWorkload(`. `Node.removeWorkload`
    ends a container too, and `LocalNode`'s own comment says `StopPodSandbox` "kills
    whatever is inside with no grace and no save". Safe today by construction —
    `WorkloadView.teardown` emits `TeardownStep.Refuse` for a running container and
    `stopSandbox` has one call site, inside `removeWorkload`, after the sandbox is
    confirmed empty — but the pin's stated property is "every container stop" and a
    `Node` implementation that skips the refuse guard is invisible to it. Same
    question as item 90: the scan's alphabet is a decision, so state it and check it
    against the operations that can actually end a process.
92. **A `when` listing every sealed subtype explicitly is a stronger extraction
    than the `if/else` it replaces, and that is the thing to check first.**
    `DrainStatus.adoptSaveClause` replaced
    `if (reading is Occupied) drain.unconfirmWorldSave() else drain`. Semantically
    identical at the one call site, but a fourth `PlayerReading` subtype now fails to
    compile instead of falling silently to the `else` — i.e. to *keeping* the
    confirmation, which is the unsafe side. When auditing an extraction, ask what a
    new case does under each form before asking whether the bodies match; the
    default branch is where the next subtype lands.

## Round 22: the widened alphabet, and the granularity it was widened at

93. **A file-granularity pin on a verb that already has a deciding file is blind to
    the second decision in that file — and that is where the motivating path
    lands.** `DrainWiringTest`'s `deciding("removeWorkload") shouldBe
    listOf(Reconciler.kt)` maps `sources.filter { … }.map { it.path }`, one entry per
    *file*, so `Reconciler.kt` already being on the list means a third, fourth or
    tenth removal decided inside it keeps the assertion green. `stopWorkload` does
    not have this hole only because a *second* test (`calls shouldHaveSize 2`, on
    `DrainController.kt`'s lines) supplies the count; `removeWorkload` has no count
    pin and no gate pin at all, and `naming(v).size shouldBeGreaterThan
    deciding(v).size` is a vacuity control that stays true. The test's own stated
    motivation is rescheduling — which is reconcile-loop work and would be written
    in `Reconciler.kt` — so the trigger the design promises is the one case it
    cannot fire on. The harness matches the test rather than the claim: D14 adds a
    removal to `DrainController.kt` (a *new* file, caught); no mutation adds a
    second removal to `Reconciler.kt`. Cheap repair: pin
    `(path, enclosing().name)` pairs, not paths — `[teardown, teardownProxy]`.
    General rule: when a pin's unit is coarser than the thing it claims to notice,
    ask whether the claimed trigger is a *new* unit or another one of an existing
    unit.
94. **Two verbs in one scan inherit different amounts of enforcement, and the KDoc
    reads as if they inherit the same.** Widening the alphabet from `stopWorkload`
    to `stopWorkload` + `removeWorkload` was right (item 91 closed), and the
    asymmetric claims are honestly written down — stop is gated, removal is only
    located. But the *strength* also differs and is not: stop is pinned by file-set
    **and** count **and** gate; removal by file-set alone. When an audit accepts
    "different verbs carry different arguments", check that the difference recorded
    is the one that exists, and not just the one about semantics.
95. **`WorkloadView.teardown`'s first guard has no not-found arm; the second one
    carries it.** `own = containers.firstOrNull { it.id == handle.containerId }`
    null-falls-through to `TeardownStep.RemoveContainer(ownId)` with **no** state
    check, and CRI's `RemoveContainer` forcibly removes a running container. The
    stale-handle case is caught by `occupants` (a differently-ided live container is
    in the list); the only slip is an enumeration that omits a running container
    entirely, i.e. round 4's same-well residual, still open and still ruled. Restate
    it whenever the removal path is re-audited: the refusal that `Node.removeWorkload`'s
    contract promises is enforced through the enumeration, not through the handle.

## Round 23: what a constructive unreachability argument can be pinned at

96. **A constructive "nothing reaches it" argument has three premises, and a
    caller-count pin carries two.** `DrainWiringTest`'s
    `stop has one caller, reached from a branch that has already asked mayStop`
    pins (a) `stop`/`letGoAndStop` are `private` with one call site each, and (b)
    the one entry's enclosing function is `step` and `step` names `mayStop`. The
    third premise — that the *value* handed down is the one `mayStop` was asked
    about — is prose only: nothing asserts `letGoAndStop`'s call reads
    `stop(pass, drain)` with its own parameter, nor that `step` applies `mayStop`
    and `letGoAndStop` to the same `drain`. Low consequence in isolation (a
    divergence either makes the backstop fire or leaves it dead), but it is the
    premise the whole "this gate needs no scenario" ruling rests on, and it is one
    line with the technique the sibling adoption test already uses
    (`callee(reading.value)` — follow the name, do not restate it). When accepting
    a constructive argument, list its premises and check each is pinned or
    disclosed; a premise about a *value* is the one that gets left in prose.
97. **The thing that actually catches the composite is an outcome assertion, not a
    gate assertion.** A narrowed primary (`step`'s `mayStop(...) || playersEvacuated`)
    is invisible to a presence check; a narrowed backstop (`stop`'s
    `!mayStop(...) && !playersEvacuated`) is invisible to everything. Both together
    issue a stop with no current save — and that is caught, by `DrainTest`'s
    `harness.node.stops.shouldBeEmpty()` assertions, precisely *because* they assert
    that no stop happened rather than which gate refused. A scenario that asserts a
    refusal by its detail string can be satisfied by the other gate refusing; one
    that asserts the runtime was never asked cannot. Prefer the outcome assertion
    for a gate that has a peer, and say in the KDoc that it is the peer's cover.
98. **`performs(verb) = name == verb && declaration.contains("override ")` closes
    the wrapper (item 90) and leaves the decorator.** A call to `stopWorkload`
    inside an `override fun stopWorkload` is exempt unconditionally, so a `Node`
    decorator that shortens the grace period on the way through
    (`delegate.stopWorkload(handle, ZERO)`) contributes no entry to the deciding
    list. `Node.stopWorkload`'s strictly-positive-grace promise is KDoc at the
    interface (`Node.kt:157`) and enforced only in `LocalNode.stopWorkload`. The
    exemption is right — it is what lets a distributed `Node` land without editing
    the test — but it means the seam CLAUDE.md protects is the one place the scan
    is blind. When the second `Node` arrives, the check to add is that every
    implementation of `stopWorkload` passes its own `gracePeriod` parameter through
    unmodified.
99. **A test-case rename has to be chased into every string that quotes it, and the
    prose one level up is a string.** The round-23 rename (…"decided in one file
    each" → …"decided at the sites named here") updated the harness's `$DECIDED`
    variable and left `scripts/dev/drain-wiring-mutations.sh:7` still describing the
    suite as asserting "every call which ends a container is decided in one file" —
    the exact claim D15 was added to refute. Same family as the maintained-lie rule
    the rename was performed under: grep the retired *claim*, not just the retired
    identifier.
100. **A seal whose failure aborts unconditionally makes an unsealable workload
    unstoppable, and for the proxy's own drain the justification does not apply.**
    `DrainController.holdSeal` aborts on any `SealOutcome` that is not `Asserted`,
    before any occupancy is consulted. Its KDoc argues the abort from a *backend*
    with a router ("transferring into a queue that refills behind it"). A
    `ProxyDrainSubject` has `router = null` and never transfers — it waits for zero
    like a standalone server — so a proxy whose control endpoint does not answer is
    parked in `DRAIN_FAILED` for ever *even with zero players confirmed by ping*,
    and is therefore unstoppable, unreplaceable and undeletable. The standalone
    Paper path is fine because `seal == null` short-circuits; the asymmetry is that
    "there is no seal" is safe and "the seal did not answer" is not. Whenever a
    step's abort is justified by a step that comes *after* it, check every subject
    type for which that later step is absent.
101. **The remediation for "the plugin is missing" needs the plugin.** Round 24's
    closed loop: a Velocity proxy with no working control endpoint can only be
    repaired by recreating it; recreating it is a `REPLACEMENT` drain; that drain
    seals through the endpoint it does not have. Nothing in the spec hash
    (`VelocityWorkloadPlanner.specHash`) mentions the asset, the environment or the
    token *value*, so the loop cannot even notice. Exit is a manual `crictl stop`.
    Live causes that survive: unpinned `VELOCITY_VERSION` (the image pulls latest
    Velocity, the plugin is compiled against velocity-api 4.0.0, and a breaking
    upstream release makes a previously-working proxy come up ready and permanently
    undrainable, with no hash input moved), the asset going missing between create
    and a later recreate, and a control-token rotation. Generalisation of memory
    item 4: whenever a capability is *delivered* by the create path and *required*
    by the drain path, ask what repairs a container that has the second but not the
    first.
102. **A value that configures nothing but participates in the spec hash and in the
    drain probe is a wedging edit.** `VelocityProxy.spec.network.port` is a *claim
    about the image* (nothing configures Velocity's `bind`), it is in
    `VelocityWorkloadPlanner.specHash`, and `VelocityProxyAgent.probe` pings it from
    the **desired** definition against the **running** container. Editing it
    therefore triggers a replacement drain and simultaneously breaks the ping that
    gates that drain's stop, so the proxy wedges on `DRAIN_STALLED` / "cannot
    confirm zero players" rather than the `READINESS_TIMEOUT` the KDoc promises.
    The KDoc's promise holds only for a *fresh* proxy. When a constant is
    reclassified from request to claim, check whether it is still hash-bearing and
    still probe-bearing; a claim that can only have one correct value belongs in the
    reader's validation, not in the hash.
103. **`Refused` is always retryable in one mapper and permanent-by-default in the
    other, and enforcement newly opened the door.** `ProxyLink.asSealOutcome` makes
    every `ControlOutcome.Refused` retryable on the stated grounds that "stop
    trying" on a drain step is how a container becomes undeletable —
    while `BackendLink.transfer`'s `else` bucket maps a refusal to
    `TransferReport.Refused(retryable = false)`, i.e. a PERMANENT drain abort. That
    bucket now contains `UNAUTHENTICATED`, which was unreachable while the plugin
    ran with `auth.required = false` and became reachable the moment
    `MCORCH_CONTROL_TOKEN` was delivered. It stays unreachable only because
    `holdSeal` issues the same 401 first and aborts retryably — an ordering
    accident, not a guarantee. Whenever an auth mechanism is switched from
    unenforced to enforced, re-walk every `else` branch over the protocol's error
    codes.
104. **A new `require` in a workload type is a new way to make a populated server
    undeletable.** `Reconciler.rejectDefinition` records `PERMANENT` and does *not*
    exempt `terminating`, and `Pass` construction happens before the delete
    exemption, so any `IllegalArgumentException` thrown while planning a workload
    makes that server permanently unreconcilable — drain included. Round 24 added
    `StorageRequest.Persistent.init { require(mountPath.startsWith("/")) }`, which
    the YAML reader already enforces more strictly but the store's
    `DefinitionCodec.readStorage` does not re-check. Prefer refusing at the node
    (`NodeException.Rejected` from `HostPaths`), which fails the create without
    freezing the delete. Before adding an `init` check to anything on the
    `WorkloadSpec` path, ask what it does to a server the operator is trying to
    remove.
105. **`WorkloadSpec.init` checks assets against assets, not against the world
    mount.** The duplicate-destination guard is `assets.distinctBy { it.destination }`
    only; nothing forbids an `AssetMount` whose destination sits at or under
    `StorageRequest.Persistent.mountPath`, and `HostPaths.mounts` appends asset
    mounts *after* the storage mount with `readOnly = true`. Unreachable today (one
    asset, proxies only, ephemeral storage), which is exactly why it will be
    reachable later. The whole premise of the round-24 change is "a path in this
    type is now a path that is honoured" — so the honoured paths have to be checked
    against each other across both fields, not within one.
106. **A create-time refusal is discovered after the old container is already
    gone.** `HostPaths.mounts` throws for a missing asset from `containerSpecFor`,
    which `LocalNode.ensureWorkload` calls *after* `runSandbox` — and, on the
    replacement path, after the previous proxy has been drained, stopped and
    removed by an earlier pass. The orphan sandbox is the small half; the large half
    is that the fleet's only front door is gone and the loop has just discovered,
    permanently, that it cannot build a new one. Nothing stages the artefact for
    `:app:run` (only `:app:integrationTest` does), so the gap is the default state.
    Whenever a create gains a new permanent refusal, ask what the *replacement*
    sequence has already destroyed by the time it fires, and whether the same
    question could have been asked before the teardown committed.

## Round 25: the pre-flight, and the hash input with no revert

107. **A pre-flight is only as good as the subset of the create it re-runs, and the
     subset it re-runs is decided by which helper was convenient.**
     `LocalNode.checkWorkload` answers `Node.checkWorkload` with `mountsFor(spec)`,
     and its KDoc promises "the same derivation the create runs". The create is
     `sandboxSpecFor` + `containerSpecFor`, and `containerSpecFor` also calls
     `resolveSecrets`, which throws `NodeException.Rejected` (permanent) for a
     `SecretRef` that is not in the store. So the pre-flight covers the plugin
     asset — which appears in **no** spec hash and therefore cannot trigger a
     replacement — and misses `forwarding.secret` and `control.token`, whose
     *coordinates* are in `VelocityWorkloadPlanner.canonicalSpec`. One operator
     edit repointing a token at a secret that does not exist yet is: hash moves →
     `REPLACEMENT` → blocker says yes → seal, drain to zero, stop, remove → create
     throws `Rejected` → `PERMANENT` → `isBlockedByPermanentFailure` freezes the
     proxy with no container. Whenever a "can this be built" question is added,
     enumerate the create's refusals and cross them against the hash: the ones
     that matter are exactly the intersection, and a pre-flight that misses the
     intersection is decoration.
108. **A permanent freeze is lifted only by a generation bump, so a remedy that is
     not a definition edit cannot lift one.** `isBlockedByPermanentFailure` is
     `previous.observedGeneration == stored.definition.generation && PERMANENT &&
     (drain == null || DRAIN_FAILED)`, minus `terminating`. That is the whole
     argument for classifying a pre-flight refusal `RETRYABLE` when the remedy is
     "stage a file" or "re-align a token" — and it is stronger than the reason the
     code gives ("needs no definition change"), which reads as a preference.
     Corollary in the other direction: the same rule means an operator who fixes
     the *cause* of a permanent create failure without touching the YAML gets
     nothing; they have to make a second, meaningless edit to be believed.
109. **A hash input that lives in source rather than in a definition deletes the
     operator's cancel.** `VELOCITY_BUILD` is in `canonicalSpec` and appears in no
     YAML, so once the container label and the constant disagree `proxyDrainCause`
     returns `REPLACEMENT` on every pass for ever. Before, a proxy replacement was
     always a definition edit, and reverting it made the cause null, ran
     `convergeProxy`, and reopened the login path. Whenever a constant joins a spec
     hash, ask what the operator types to cancel the replacement it triggers — and
     if the answer is "edit the orchestrator", the constant belongs in the schema
     with a default, or the hash needs a version so old rows are grandfathered.
110. **A seal whose only un-asserter sits on the converge path becomes permanent
     the moment the drain cause is one converge can never clear.**
     `assertProxyAdmission(admits = true)` is reachable from exactly one place —
     `Reconciler.assertBackends`, called from `awaitProxyReady`, i.e. `cause ==
     null`. `DrainController.holdSeal` asserts `admits = false` on every pass of a
     proxy drain. So a proxy drain that blocks (players online) or parks (any
     abort) is a fleet with no logins, and a drain whose cause cannot be cleared is
     a fleet with no logins for ever, on a container that is running and healthy.
     Danger-pattern 33's compensation obligation, unpaid for the *proxy's own*
     seal: `abort` restores a backend's registration and restores nothing here.
     Whenever a level trigger is the only compensator, check that the state it
     compensates from can still reach the path the trigger lives on.
111. **Moving a refusal out of a type `init` closes the freeze and opens a
     teardown-then-fail, and the check is which kind got the pre-flight.** Round 24
     rightly moved `StorageRequest.Persistent.mountPath`'s `require` to
     `HostPaths.checkMountPlan` (item 104 closed). But `storage.mountPath` is in
     `PaperWorkload.specHash`, and `Reconciler.reconcilePaper` has **no**
     `replacementBlocker` — so the same bad value that used to freeze a running
     server with its container untouched now drains it, stops it, removes it and
     then permanently fails the create. Same for `rcon.secret`, and for the
     *proxy's* `forwarding.secret`, which is in **every backend's** hash. The world
     survives (nothing deletes a volume directory) so it is availability rather
     than data loss, but it is the exact sequence the new pre-flight exists to
     prevent, on the kind that holds worlds. When a refusal is relocated from
     "before the pass" to "at the create", check every kind whose hash carries the
     value, not just the one the fix was written for.

## Round 26: the compensation that removes the mechanism of the wait

112. **A compensating edge keyed on "abort vs block" is keyed on the wrong axis;
     the axis is "will this drain be retried".** `DrainController.abort` calls
     `releaseSeal` unconditionally, and its KDoc justifies the asymmetry with
     "an abort has stopped advancing, so the seal buys nothing". That sentence is
     true of a `PERMANENT` abort — `isBlockedByPermanentFailure` genuinely stops
     the passes — and false of a `RETRYABLE` one, which re-enters on the very next
     pass. `abort` already takes `failureClass` and does not consult it. Whenever a
     compensation is added to a park, ask which of the two park classes its
     argument is about, and check the parameter that already distinguishes them is
     in the condition.
113. **Releasing a level-triggered seal is only safe if the path that re-asserts it
     is reachable from where the release leaves the drain.** After
     `releaseSeal`, the drain sits in `DRAIN_FAILED`; `step`'s `DRAIN_FAILED`
     branch is `resume(gated = router == null)` → `requireEmpty` → `blocked` for a
     routerless subject with anybody online, and `holdSeal` is called only from the
     six forward states. So one retryable abort with players on releases the login
     seal and **no later pass can re-assert it until a pass reads zero** — which,
     with the door open on a live fleet, never happens. The self-healing wait
     becomes a non-converging one, and the only exit is the `crictl stop` the
     design exists to avoid. The general check: for every state a compensation can
     leave the machine in, walk forward and find the line that undoes it.
114. **A pre-flight that names one helper as "the create's whole derivation" is a
     claim about the create's *call list*, not about that helper.**
     `LocalNode.checkWorkload` runs `containerSpecFor`, and `ensureWorkload` runs
     `sandboxSpecFor` + `prepareHostPaths` + `containerSpecFor`.
     `prepareHostPaths` throws `NodeException.Rejected` (permanent) for an
     unwritable or missing volume/log root, and no pre-flight asks it. Narrow today
     (a replacement's directories already exist, so `createDirectories` is a no-op)
     but the KDoc's promise — "a third refusal added tomorrow is pre-flighted
     without anybody remembering to come back here" — holds only for refusals added
     inside the one helper named. Enumerate the create's *calls*, not its
     derivation.
115. **`PRESENCE_ONLY` makes the pre-flight's derived object structurally
     different from the create's, so any validation over the omitted field is
     blind.** `secretsFor(PRESENCE_ONLY)` returns `emptyMap()`, so the pre-flight's
     `ContainerSpec` is built without the secret env entries, and
     `ContainerSpec.init`'s `require(env.keys.none { it.isBlank() })` cannot see a
     blank `secretEnv` key. Unreachable today (the keys are compile-time
     constants), and it would surface as a `RuntimeException` that `translating`
     maps to a permanent `Rejected` after the teardown. When a pre-flight variant
     drops a field, list the validations that read that field.
116. **A drain-in-flight exemption is justified by the end of the drain and applied
     from its beginning.** `replacementBlocker` returns null once
     `pass.previous?.drain != null`, on the grounds that "the container it would
     have saved is gone or going". That is true from the stop onwards and false for
     every pass before it — sealing, waiting for players, saving — which on a busy
     server is hours. The artefact or secret can vanish inside that window and the
     teardown still commits. If the cheap question exists, the place it belongs is
     immediately before the irreversible step, not only before the reversible ones.

## Round 27: the class-keyed compensation and the clause the gate has that the key does not

117. **A compensation keyed on `FailureClass.PERMANENT` inherits `isBlockedByPermanentFailure`'s
     *whole* predicate, and that predicate ends in `&& !stored.definition.terminating`.**
     Round 26 moved `DrainController.abort`'s `releaseSeal` behind
     `failureClass == PERMANENT` on the argument that a permanent abort stops this
     server's passes, so nothing will ever re-assert the seal. `Reconciler.Pass.
     isBlockedByPermanentFailure` is `failed && !terminating` — so for a **deleted**
     workload a permanent abort stops nothing: the loop keeps reconciling, the door
     is reopened by the compensation, players refill through it, and the gated resume
     (`resume(gated = router == null)` → `requireEmpty` → `blocked`) can never reach
     `holdSeal` again. `blocked` then clears the failure, so the permanent diagnosis
     that caused it disappears and the drain reads "waiting for players, nothing
     wrong" for ever. Danger pattern 113 with `PERMANENT` in place of `RETRYABLE`,
     on the one cause — delete — where non-convergence is worst. General rule: when a
     compensating edge is keyed on the *input* to a gate rather than on the gate's own
     answer, copy the gate's whole predicate or call it; a trailing `&& !x` clause is
     exactly what gets dropped.
118. **A seal that is only asserted from the six forward states is asserted at most
     twice in a wait that lasts hours.** For a routerless subject the `DRAIN_FAILED`
     branch is `requireEmpty { resumeInto }`, so a pass with anybody online lands in
     `blocked`, which does not seal. `holdSeal` therefore runs only while the fleet
     reads zero. Consequences: (a) a proxy drain whose control endpoint was down on
     its *first* pass with players on never asserts the seal at all and can never
     assert it later — the door stays open, the population never reaches zero, and
     the drain does not converge even after the endpoint recovers; (b) any loss of
     the plugin's in-memory `AdmissionRegistry` during the wait is unrepairable by
     the same route (mostly closed by `containerIsDown`, which ends the drain when
     the container exits). The rejected alternative — `holdSeal` before `requireEmpty`
     on the gated resume — costs only turning a healthy `blocked` into a retryable
     abort while the endpoint is down, which is a reporting change, not a door
     opening, now that the release is permanent-only. Re-take that trade against the
     *current* release rule rather than against the unconditional one it was argued
     under.
119. **The operator sentence that survives a compensation change is the one that
     described the old behaviour.** `abortSeal`'s abort message still reads *"The
     server keeps running and keeps taking players"*. True while a retryable abort
     released the seal; false now, and the case it is false in is a proxy whose front
     door is shut fleet-wide. Item 27's direction test says over-stating availability
     errs safe, and it does — but here the over-statement hides the *only* symptom
     (nobody can log in) from the one surface an operator reads. Whenever a
     compensating edge is narrowed, grep the messages written on the paths that no
     longer run it.
120. **A pre-flight moved to the entry of a two-pass step is asked once per pass of
     that step, including the pass after the irreversible half already ran.**
     `replacementIsBuildable` guards `letGoAndStop`, which is re-entered in
     `DEREGISTERED` after a successful deregistration (`deregisteredAt != null`), and
     is also re-entered after a resume out of `STOPPING`. So its message — *"Nothing
     has been stopped, removed or deregistered"* — is written on passes where a stop
     has been issued and its grace period is running, and on passes where the
     re-registration in `abort` failed. Both err on the safe side of item 27
     (over-stating availability), and the flap the KDoc worries about is genuinely
     absent because the second refusal finds `deregisteredAt` already cleared. Check
     the re-entry set of any step a new guard is placed at the entry of.

## Round 28: the new assertion site that records nothing, and the compensation that fails inside its own gate

121. **A second site that asserts a level-triggered seal, without writing down that
     it did, silently falsifies every reader of the record.** Round 28 put
     `holdSeal` ahead of `requireEmpty` on the gated resume
     (`DrainController.resume`), which is right — it is the only place a routerless
     subject can re-seal while somebody is on. But only `DRAIN_REQUESTED` stamps
     `sealRequestedAt` (`.copy(sealRequestedAt = if (sealed) … else null)`), and the
     new site stamps nothing. `loginPathAfterAPark` keys its three messages on that
     field, so the sequence *first seal fails with players on → park → resume seals
     successfully → block → endpoint drops again → abort* prints **"The server keeps
     running and keeps taking players"** about a fleet whose front door this
     controller shut one pass earlier. Danger pattern 119 re-created by the fix for
     119, through a call site rather than through a stale sentence. Whenever a
     level-triggered assertion gains a second call site, find every field that
     records the assertion and every reader keyed on it: the *state* is now asserted
     from two places and the *record* from one.
122. **A best-effort compensation guarded by "no pass will look at this again" is
     unrecoverable in exactly the case where it fails.** `abort` runs
     `releaseSeal(subject)` under `PERMANENT && permanentFailureStopsPasses`, and
     `releaseSeal` discards its outcome — a refused or unanswered `PUT /v1/proxy`
     is a log line. The abort is recorded permanent anyway, so
     `isBlockedByPermanentFailure` freezes the proxy and no later pass retries the
     release. One transient timeout on that single call therefore leaves the fleet's
     login path shut with the loop no longer looking at it, and a definition edit
     does not repair it — the generation bump resumes the passes straight into
     `resume`'s `holdSeal`, which shuts the door again. The contrast that proves the
     shape: `restoreRegistration` is best-effort too and is *safe*, because the
     proxy's `assertBackends` sweep re-registers a parked backend on every pass.
     Rule: where the compensation has no third-party repairer, the class must depend
     on whether the compensation landed — a failed release should demote the abort to
     `RETRYABLE`, or the release must be retried from a state the loop still visits.
123. **`blocked`'s unconditional `failure = null` erases a *permanent* diagnosis and
     the escalation anchor with it, and the "the door is now shut" defence does not
     cover the subjects that reach it.** The argument for tolerating it — a wait
     whose seal is held is a real wait — is about the proxy. A standalone Paper
     server has `seal == null`, so `holdSeal` returns `NothingToSeal` and the
     population is free to refill. Under a delete (`permanentFailureStopsPasses` is
     false, so the passes carry on) the sequence is: save requested, never confirmed
     → `PERMANENT` `DRAIN_SAVE_TIMEOUT` → somebody logs on → `blocked` clears the
     failure and `requireEmpty`'s message says *"the drain resumes on its own once it
     is empty"*, which is false: `save`'s `saveRequestedAt != null` branch aborts
     permanently again. The wedge itself survives (nothing stops), so this is a
     reporting defect — but it is the report that decides whether an operator reaches
     for `crictl stop`, and every flap of the population also resets
     `FailureStatus.occurredAt`, so a retryable fault on a busy server never reaches
     `drainAttentionAfter`. Narrow the clear to `RETRYABLE`, or carry the wedge into
     the block message.
124. **A remedy sentence keyed on the subject's shape names an action the *cause*
     may not permit.** `loginPathAfterAPark`'s sealed branch offers "until whatever
     asked for this drain is withdrawn". A `REPLACEMENT` can be withdrawn — revert
     the edit, `proxyDrainCause` returns null, `assertBackends` re-admits. A
     `DELETION` cannot: `deletedAt` is one-way and there is no un-delete route in
     `:api`. So the one operator-facing sentence about a fleet-wide blackout names a
     remedy that does not exist in the case where the blackout lasts longest. When a
     message is keyed on `router`/`seal` state, check it against each `DrainCause`
     as well.

## Round 29: the record that re-arms the gate

125. **Making a park's record *more honest* can re-arm `isBlockedByPermanentFailure`,
     and the fix's own reachability argument is narrower than the fix.** Round 28
     asked `blocked` to stop erasing a **permanent** failure. It now writes
     `copy(blocked = block, failure = standing)`, `Reconciler.drain` copies
     `progress.drain.failure` into `status.failure`, and the gate is
     `observedGeneration == generation && PERMANENT && (drain == null || DRAIN_FAILED)
     && permanentFailureStopsPasses` — a block parks in `DRAIN_FAILED`, so the pass
     that records the block arms the gate for the next one. The KDoc argues
     reachability only for a **delete** (where `permanentFailureStopsPasses` is false
     and nothing freezes), but the *other* way a pass runs with a permanent failure
     standing is a **generation bump**: the operator's edit buys exactly one pass, and
     if anybody is online that pass spends it on a block instead of on the step the
     edit was meant to repair — then re-freezes with the edit consumed. For a
     self-sealing proxy the gated resume shuts the login path *before* `requireEmpty`
     (round 28's own fix), and `releaseSeal` lives in `abort`, which a block never
     reaches, so the frozen state is a fleet-wide blackout that no pass can lift and
     that every further edit re-creates. The generalisation: `blocked`'s KDoc
     enumerated `StatusDrafting` and `:api` as the consumers that "already rank the
     two" and missed the one consumer that reads the class to **stop reconciling** —
     danger pattern 67, from the other end. Whenever a record is widened, grep the
     gate, not just the renderers. Smallest correct narrowing: retain the permanent
     failure only where the fix's own argument holds (`!permanentFailureStopsPasses`),
     or carry the wedge in the block's message and not in `drain.failure`.
126. **A compensation that only runs from one branch is not "retried next pass" if
     another branch is reachable first.** `abort`'s `heldShut` downgrade
     (`PERMANENT` recorded `RETRYABLE` when `releaseSeal` did not land) is sound —
     it is confined to self-sealing subjects, because `releaseSeal` returns false for
     `router != null` and `Reconciler.drain` passes one `link` as both `seal` and
     `router`, so no world-holding Paper subject can be reclassified — but its
     message and KDoc promise "the loop keeps coming back and releases the seal on the
     first pass that reaches the endpoint". The release is reachable **only** through
     `abort`; the pass after a park runs `resume` → `holdSeal` → `requireEmpty`, and
     with anybody online that lands in `blocked`, which skips the release *and* clears
     the retryable record that named the stuck door. Before believing "the
     compensation is attempted again next pass", walk the next pass to the line that
     calls it.
127. **A bound stated once is not the bound the system runs on, and the new one is
     also a transport deadline.** `StopGracePeriod.MAX_SECONDS` (9_223_372_036) is
     containerd's arithmetic boundary, not a policy — and
     `GrpcCriClient.stopContainer` derives the gRPC deadline as
     `gracePeriod.duration + deadlineSlack`, so a legal-but-absurd grace period parks a
     reconcile worker for centuries with no timeout, which is the one thing CLAUDE.md
     says every `:cri` call must have. The only operational ceiling is the YAML
     readers (`MAX_STOP_GRACE_PERIOD` 2h, `VelocityProxyDefaults.MAX_TIMEOUT`), and
     unlike the *minimum* — stated in `SpecInvariants` and enforced twice, at parse and
     in `LifecycleSpec.init` — the maximum is enforced **once**, so a store row that
     did not come through a reader carries anything. Do not close it with a new
     `init` `require`: `Reconciler.rejectDefinition` records `PERMANENT` and does not
     exempt `terminating` (danger pattern 104), so that trade makes a populated server
     undeletable. Close it at the decode (tolerant, one server) or at the node.

## Round 30: the ceiling that inverts the relation, and the sentence that came back

128. **A bound applied at one end of a cross-field invariant inverts it, and it
     inverts it on exactly the population it fires for.**
     `StopGraceCeiling.bound` caps `stopGracePeriod` at 2h in `LocalNode.stopWorkload`.
     The one cross-field rule the schema calls a data-loss rule —
     `SpecInvariants.stopGraceProblem`, *"a grace period shorter than the save
     timeout kills the container part-way through the save"* — is enforced in
     `LifecycleSpec.init`, i.e. on the *uncapped* pair, and the node cannot see
     `saveTimeout` at all (`Node.stopWorkload` takes a handle and a duration). So
     `saveTimeout = 3h, stopGracePeriod = 3h1m` decodes, passes the `init`, and
     reaches containerd as 2h — below the save timeout. `DrainSpec` has **no**
     `init` bounding `saveTimeout`; only `PaperServerReader` does. And the cap only
     ever fires on a definition that did not come through a reader, which is the
     same population that can carry an oversized `saveTimeout`: the trigger
     condition selects for the rows where the inversion is possible. Whenever a
     clamp is added to one field of a validated pair, apply it as
     `max(ceiling, whatever the pair's rule requires)` or apply it where both
     fields are visible (the decode), never at the consumer that sees one of them.
129. **The same unvalidated row supplies a second transport deadline, and only one
     of the two got a ceiling.** The ceiling's own argument is that
     `GrpcCriClient.stopContainer` derives its gRPC deadline as
     `gracePeriod + deadlineSlack`, so an absurd grace period is a worker parked
     with no effective timeout. `GrpcCriClient.execSync` does the identical thing —
     `commandSeconds.seconds + deadlineSlack` — and its input is
     `spec.lifecycle.drain.saveTimeout` (`PaperServerAgent.requestSave`), from the
     same row, with the same absent type-level bound, on the *longer* of the two
     calls. A fix derived from "this number becomes a deadline" has to be applied to
     every number that becomes one; grep the `:cri` deadline derivations, not the
     one the finding was written against.
130. **A retired safety sentence comes back in the KDoc of the next thing that needs
     it.** `DrainController`'s class note says, in as many words, that *"no path
     reaches `Node.stopWorkload` except through `requireEmpty` followed by
     `mayStop`"* was false from the day `awaitStopped`'s re-issue was written, and
     that the count is held by `DrainWiringTest` rather than by prose. Round 30's
     `StopGraceCeiling` KDoc — where it is the *whole* argument for capping rather
     than refusing — states it again as "nothing reaches `Node.stopWorkload` except
     through the zero-player gate followed by `mayStop`". The substance survives
     (both gates end in `mayStop`), so it is a warning and not a critical, but the
     new site does not carry the qualification the old one was corrected into, and
     the second gate lets an *unanswered* probe through. When a safety argument is
     borrowed into a new file, borrow the correction with it or link to the
     paragraph that holds it.
131. **Verifying a narrowing needs both directions, and the second one is "what does
     the newly-reachable pass do".** `parkedOnTheFailure()` is provably a subset of
     the clause it replaced (`null || state == DRAIN_FAILED` vs
     `null || (state == DRAIN_FAILED && blocked == null)`), so
     `isBlockedByPermanentFailure` can only un-freeze. What makes that *safe* is
     three separate facts, none of them in the gate: `blocked` is written only by
     `DrainController.blocked`, which always parks in `DRAIN_FAILED`, and
     `settleRecords` clears a stale block on every non-parked pass, so the new
     clause cannot bite outside `DRAIN_FAILED`; `abort` does
     `copy(failure = …, blocked = null)`, so a genuine permanent abort still arms
     the gate and there is no wedge; and every block runs through
     `readPlayers`' `Occupied` arm → `forgetSaveEvidence`, which clears
     `worldSavedAt` **and** `playersEvacuated`, so the resume ladder after a block
     can only land on `SEALED`/`TARGET_RESOLVED` and a fresh confirmed save is
     always taken before the stop. Re-check all three before accepting any future
     widening of what `blocked` records.

## Round 31: the floor that unmakes the ceiling, and a licence written on a type

132. **A ceiling given a floor derived from a second unbounded field stops being a
     ceiling over the whole reachable range of that field, and the residual gets
     stated at the unreachable end.** Round 30's finding was right — clamping
     `stopGracePeriod` without seeing `saveTimeout` inverts a validated pair — and
     the fix is `ceilingFor(saveTimeout) = max(MAX, saveTimeout + MIN_STOP_GRACE_MARGIN)`.
     But `saveTimeout` has no type-level bound either, so for **every** value in
     `(2h, ~292y)` the derived ceiling is above what `StopGracePeriod.of` refuses and
     the declared grace goes to `GrpcCriClient.stopContainer` uncapped — whose gRPC
     deadline is `grace + slack`. `saveTimeout = 30d, stopGracePeriod = 31d` is
     `LifecycleSpec.init`-legal, decodes from a Long-nanos store column, and parks a
     reconcile worker for a month with no effective timeout: the exact CLAUDE.md
     property the ceiling was written for. `StopGraceCeiling`'s KDoc names the
     residual as "292 years away, so it needs both halves absurd" — that is the
     *refusal* end, reachable only inside a sub-second band at the top of what a
     Long-nanos column can express. The trade is still the right way round (a parked
     worker loses no world; an inverted pair loses one), but whenever a bound is
     relaxed by a floor, work out the range over which the bound now does nothing and
     state *that*, not the far end where a different rule takes over. The structural
     answer is to stop deriving the transport deadline from the policy value: a
     `stopContainer` deadline of `min(grace, MAX) + slack` with the full grace in the
     CRI `timeout` field gives both properties — at the cost of a `DEADLINE_EXCEEDED`
     on a legitimately long grace, which is a `:cri` decision, not a drain one.
133. **A safety licence written on a *type* is a claim about every value of that
     type, and `ExecTimeout`'s is true of only one of its three users.** The KDoc's
     whole argument for capping — *"an exec timeout only authorises waiting, so
     cutting it short can do no more than withhold a confirmation"* — holds for
     `save-all flush`. It is false for the **probe** exec, which is the other two
     `ExecRequest` sites: a probe that runs out of time is `ProbeOutcome.Unanswered`
     → `PlayerReading.Unanswered`, and two places read that as permission rather than
     as a withheld answer — `save`'s re-probe stamps `worldSavedAt` on
     `Empty, Unanswered` alike (so a timed-out probe *mints* the confirmation the
     stop is gated on), and `awaitStopped` lets it fall through to the re-issue.
     Unreachable today only because both probe timeouts are private 10-second
     constants against a one-hour ceiling. When a bound moves from a call site to a
     type, re-derive the licence at every construction of that type, not at the one
     the finding was written against.
134. **"Every construction site is a compile-time constant" is a survey of call
     sites, and this one was wrong.** `EndpointRequest.timeout` was left unbounded on
     the stated grounds that no definition field feeds it. It is fed by one:
     `ControlChannel` is built at `Reconciler.channel` and `ProxyFleet` with
     `timeout = definition.spec.backends.drain.sealTimeout` (`BackendDrainSpec`, no
     `init`, reader-only max), and `LocalNode.send` makes it the sole bound on a
     *blocking, uncancellable* `httpClient.send`. Same store row, same absent
     type-level bound, same population as the other two. Read the constructor's
     arguments at every call site; a parameter that is a constant in the type that
     declares it says nothing about what is passed to it.

## Round 32: the compensation that needs a fact the record does not hold

135. **A "was this side effect issued" test that is split across two catch sites needs
     both halves, and each half misses the other's case.** `restoreRegistration` must
     not re-admit players to a container that has already been sent `SIGTERM`, and the
     discriminator is *"was a stop dispatched"*. `drain.state == STOPPING` misses
     `stop`'s own `NodeException.Timeout` catch, where the drain is still
     `DEREGISTERED` — the case `reconciler-dev` correctly identified. But
     `failure is NodeException.Timeout` alone misses `awaitStopped`'s re-issue catch,
     where a `Rejected`/`Busy` on the *second* stop still follows a *first* stop that
     returned. The right test is the **disjunction**; ruling at round 32. The structural
     answer is a recorded `stopDispatchedAt` on `DrainStatus`, so the compensation tests
     a field instead of inheriting a paragraph. Whenever a compensation's correctness
     rests on "which call site am I reached from", enumerate *every* call site and check
     the proposed discriminator against each — a discriminator derived from one bug
     report covers one site.
136. **A reachability argument can be killed by a change in the same round, and the KDoc
     will not notice.** `restoreRegistration`'s KDoc justifies its residual as *now
     deterministic for a grace period above two hours*, because `:cri` caps the stop
     deadline there. But `SpecBounds` (same round) caps a stored `saveTimeout` at 1h,
     so `StopGraceCeiling.ceilingFor` returns 2h, so `StopGrace` never exceeds 2h, so
     `GrpcCriClient`'s `capped` is never true for any definition the `Reconciler` acts
     on — every one comes from the store. The residual is still real, through an
     ordinary retryable `Timeout` on a slow containerd, but the *stated* population is
     empty. Before accepting "this became reachable/unreachable because of X", re-run
     the arithmetic through every bound the same change added; a finding closed on a
     stale reachability claim is a finding closed one round early.
137. **A clamp that rebuilds a spec by full construction resets any field it does not
     enumerate.** `SpecBounds.boundPaper` uses `.copy(...)`; `boundProxy` writes
     `BackendDrainSpec(seal, destination, deregister)` positionally-by-name. Correct for
     today's three fields and silently wrong the day a fourth is added — reset to its
     default on exactly the rows a clamp fired for, with no compile error. This is the
     "one of three identically-shaped siblings" recurrence the fix was written to stop,
     reproduced inside the fix. Grep every spec rebuild in a bounding/migrating path for
     full construction rather than `copy`.
138. **Verified this round, so do not re-derive from scratch:** `PropertyDocument`
     cannot produce a non-finite `Duration` (`require(isFinite())` on write,
     `long(key)?.nanoseconds` on read), so every `!isFinite()` passthrough in a
     store-fed clamp is unreachable from a row; `IMMEDIATE_KILL` has no call site in
     `:core`/`:api`/`:app`; there are exactly two `Node.stopWorkload` call sites and
     both are preceded by `mayStop`; `Reconciler.kt`'s `letGo = deregisteredAt != null`
     closes danger pattern 55, so *not* restoring a registration keeps a backend out of
     routing without a second mechanism, and `converge`'s `drain = null` is the recovery.

## Round 33: the record that governs a compensation, and the path that deletes it

139. **A "was this side effect issued" record is only as durable as the *whole* record
     it lives in, and the reconcile loop deletes that record whenever the drain's
     cause is withdrawn.** `DrainStatus.stopDispatchedAt` (round 33) correctly closes
     `restoreRegistration` — but `restoreRegistration` is not the only way a backend
     gets back into the routing table. `drainCause` returns null the moment the
     container's `specHash` label matches the desired spec again (an operator
     reverting the edit that started the `REPLACEMENT`), `Reconciler` then routes to
     `converge` instead of `drain`, `drainController.advance` is never called, and
     `awaitJoinable`'s local `status(...)` writes `drain = null` on **every** branch —
     `Joinable`, `NotJoinable`, `Unavailable` alike, before any evidence is gathered.
     `stopDispatchedAt` and `deregisteredAt` go with it, `ProxyPass.backends` computes
     `letGo = false` and `sealed = false`, and `assertBackends` re-admits players to a
     container inside its stop grace period whose shutdown save already ran. Round 32
     recorded `converge`'s `drain = null` as *the recovery* for a stranded backend;
     round 33 made the same line the destroyer of the only guard. The general rule:
     when a field is added so a compensation can test a fact instead of inheriting a
     paragraph, enumerate every writer of the field's **container**, not of the field.
     A `copy(x = null)` you never wrote is still a clear.
140. **"The drain is no longer wanted" and "the stop has already been dispatched" are
     different questions, and only one of them is the operator's to answer.** A
     `REPLACEMENT` is withdrawable by reverting the edit (danger pattern 124 relies on
     this) — right up to the instant `stopWorkload` returns. After that the container
     is going away whatever the store says, and honouring the withdrawal converts a
     drain into a *cancelled* drain that nothing cancelled. Any "this drain can be
     abandoned" branch needs the irreversibility test, not the desire test: once a
     stop is dispatched the pass must keep draining to `containerDown` and let the
     teardown-then-create apply the reverted definition.
141. **A cost declined as "that population is already clamped" has to be checked
     against what the clamp actually clamps.** Round 33 declined a
     "this node refused before issuing" property on `NodeException` because a stop
     refused on a bad `stopGracePeriod` is a population `SpecBounds` covers.
     `SpecBounds` applies **ceilings only** and says so in a section header — flooring
     would invert the validated pair. `DrainSpec` has no `init`, and
     `LifecycleSpec.init` checks only the *relation*, which a negative pair satisfies
     (`saveTimeout = -10m, stopGracePeriod = -5m`). That row decodes, passes
     `SpecBounds` and `StopGraceCeiling` untouched, and is refused by
     `StopGracePeriod.of` inside `LocalNode.stopWorkload` — after the pre-RPC stamp.
     Same shape as item 136: a reachability/coverage claim about another module's
     bound, taken without re-running its arithmetic.
142. **A pre-RPC stamp records "a call was attempted", not "a request left this
     process", and the node has a success path that issues nothing.**
     `LocalNode.stopWorkload` returns early and *successfully* when
     `handle.containerId == null`. Unreachable through `advance` today, and it errs
     safe (the backend stays out of routing), but the field's KDoc makes the ordering
     its entire content, so the one node path that breaks the equivalence belongs in
     it.

143. **One observation with two meanings, classified by state alone.**
     `WorkloadState.SANDBOX_ONLY` means either "the container is genuinely gone and
     the sandbox is left over" or "the runtime is not reporting a container that is
     still serving players". The loop already knows the discriminator and uses it in
     `containerIsDown` (`hadContainer` = a recorded `runtime.containerId`), and the
     partial-removal branches of `teardown`/`teardownProxy` deliberately null that
     field so the next pass reads it correctly. Any *new* rule that switches on
     `WorkloadState` must be checked against that pair: a `when` over the five states
     that puts `SANDBOX_ONLY` on the "nothing is running" side without asking
     `hadContainer` contradicts the drain's own rule, and the two functions then
     disagree about the same observation. Round 34: `stopIsInFlight` does exactly
     this, which re-opens the round-33 re-admission critical through a narrower door.

144. **A guard keyed on `state == RUNNING` stops guarding the moment the container
     exits — including when the exit is the very stop the guard was refusing to
     authorise.** `forbiddenTransition` refuses `persistent → ephemeral` only while
     the container is `RUNNING`. A stop already in flight ends that window by itself,
     so the refusal expires without the operator doing anything and the transition is
     applied on the pass after the teardown. Ask of every refusal: what ends the state
     it is conditioned on, and is that thing the drain itself?

145. **A remedy that stops a loop gate arming turns "frozen" into "applied".**
     Retaining a drain record made `parkedOnTheFailure()` false, which unarmed
     `isBlockedByPermanentFailure`. A permanent refusal that used to freeze the server
     now lets passes continue — and a refusal that only fires in one container state
     is then out-waited. When a change alters what `parkedOnTheFailure()` returns,
     re-audit every `FailureClass.PERMANENT` site that was relying on the gate to make
     its refusal stick.

146. **Mutation harnesses cannot see a missing discriminator.** Flipping
     `SANDBOX_ONLY -> false` to `true` is a scored entry (D52); adding the
     `hadContainer` argument that the clause needs is not any flip of the expression,
     so a green mutation board says nothing about it. When a rule approximates a fact
     the codebase models exactly somewhere else, compare the two rules by hand.

147. **`FakeNode` derives container ids from the server name
     (`"container-${spec.server}"`), so they are identical across recreations.** Any
     rule keyed on container identity is true-by-construction in the core unit
     harness, and the defect it exists to prevent — a record outliving the container
     it names — is invisible there. Fix the fake before proposing an id-keyed record.

## Round 35: the discriminator threaded, and the guard that is not total

148. **A fix that replaces a derived value with a remembered one, then restores the
     derivation behind a `?:`.** `forbiddenTransition` stopped drafting
     `pass.storageStatus(observation)` — which is computed from the *edited*
     definition and so recorded `persistent = false, volumeName = null` on every
     pass that refused the edit — in favour of `pass.previous?.storage?.copy(bound
     = true)`. The elvis is the whole of the old expression, and it fires exactly
     where there is no record to protect: a status row decoded with `storage = null`
     (`StatusCodec.readStorage` returns null when `storage.persistent` is absent, so
     every row written before the field existed). On those rows the refusal writes a
     *false* `persistent = false` instead of writing nothing, and `draft`'s own
     default is already `previous?.storage`, so the fallback buys nothing. Same
     family as item 64: read the elvis, not the signature.
149. **A guard keyed on a container label is total only over the states that have a
     container.** Widening the storage-transition refusal from `RUNNING` to
     `RUNNING/EXITED/UNKNOWN` closes the window the drain itself opens by stopping
     the process — but `Absent`, `SANDBOX_ONLY` and `CREATED` remain pass-through
     because there is no container label to read, and two of those are also windows
     the drain opens: the pass or two between `teardown` completing and `converge`
     creating, and the partial-removal state. An `ephemeral` edit landing there is
     applied with no refusal at all, and the server comes back on an empty world.
     The loop's own memory of "this server had a persistent volume" is
     `status.storage`, which `converge` (`Reconciler.kt` ~1761) and `drain`
     (~2141) overwrite from the *desired* definition every pass — so a create-side
     guard has nothing to ask. Ruling: the local fix is right, the general case is
     not closable while `StorageStatus` is documented as observed and derived from
     desired.
150. **A refusal placed in front of `advance` is a gate on the drain, not only on
     the edit.** `forbiddenTransition` returns before `drainController.advance`, so
     while it fires the drain cannot reach `awaitStopped` or `teardown`. With the
     dispatch record now retained across it, a refusal landing inside a stop's grace
     period leaves the container signalled, the backend deregistered, the sandbox
     un-torn-down and the workload dark until an operator reverts — no world is
     lost, and nothing but the revert lifts it. The KDoc's "the gate does not arm so
     passes keep coming" is true only for that retained-record case; with no
     dispatched stop the record is cleared to null, `parkedOnTheFailure()` is true
     and the server freezes instead. Two different outcomes, one paragraph.
151. **Eleven sites delete a mid-flight drain record, and that is safe only while
     every external drain step is level-triggered.** `clearedDrainRecord` returns
     null whenever no stop was dispatched, so any converge, joinable or refusal pass
     wipes a drain that had sealed a backend, chosen a destination and issued a
     transfer sweep — without going through `DrainController.abort`'s
     compensations. It holds up today because `ProxyPass.backends` derives both
     `sealed` and `letGo` from the stored record every pass, so deleting it restores
     routing by itself. The day a drain step gains an effect that is *not*
     re-derived from the record (a kick, a persisted transfer ticket), these eleven
     sites skip its undo silently. Same obligation as item 33, at the sites that
     were never thought of as abort paths.
152. **Mutation boards score inversions; this subsystem now produces omissions.**
     Rounds 33, 34 and 35 each turned on a *new consumer asking a narrower question
     than the fact supports* — a missing argument, a state left out of a `when`, a
     guard conditioned on one state. Round 34's own note (item 146) says a board
     cannot see a discriminator nobody wrote. The check that has found the last two
     criticals is the same one both times: enumerate every exhaustive
     `when (…WorkloadState)` in `:core/main` and ask each whether it consults
     `hadContainer` or carries a written unreachability argument *at the arm*. There
     are five: `containerIsDown`, `stopIsInFlight` (both take it),
     `forbiddenTransition`'s `couldBeTheContainerTheEditIsAbout`, and `converge` /
     `convergeProxy` (whose `SANDBOX_ONLY` arms are protected by the routing above
     them, and whose argument lives at the routing site rather than at the arm).


## Round 36: the instrument's own alphabet, and a residual priced in a sibling's currency

153. **A field whose absence "costs a player's session" was given the residual
     paragraph of the fields whose absence costs a cycle.**
     `DrainStatus.stopDispatchedAt` (Status.kt) argues at length that losing it
     "errs towards re-admitting players to a container that is shutting down — the
     direction to design against", and then closes with *"a row written before this
     field existed reads null, so a drain caught mid-stop by an upgrade can
     re-admit once — the same one-cycle cost every anchor here pays"*. It is not the
     same cost: `resaveForcedAt` and `transferStartedAt` pay a cycle, this one pays
     the thing the field exists to prevent, and it is not "once" — the record is
     *deleted* by `clearedDrainRecord` on that pass and nothing restores it, so the
     backend stays admitting for the rest of the grace period. `StatusCodec` writes
     the field into a *document*, not a column, so no migration version changed when
     it was added and no `Migration` backfills it. Whenever a residual for a new
     optional field is written by analogy to the fields above it in the same class,
     check that the analogy carries the *direction* of the error and not just the
     shape of the sentence.
154. **The rejected-discriminator argument is direction-sensitive, and reusing it at
     the decode reverses it.** `state == STOPPING` was correctly refused as the
     call-site discriminator because it *under*-reports a dispatch (a stop whose
     deadline elapsed leaves the drain `DEREGISTERED`). Using it at the *decode*,
     only for a document that has no `stopDispatchedAt` key at all, *over*-reports —
     which withholds a re-admission and keeps the drain running to `containerDown`.
     "That proxy was rejected" is not an argument against the safe-direction use of
     the same proxy in a different position.
155. **A structural scan has an alphabet, and three writable shapes are outside this
     one.** `DrainWiringTest`'s state-arm instrument reads *the arm's own line* and
     requires the literal `WorkloadState.` in the pattern. Verified by replicating
     the scan and mutating: (a) a formatter-wrapped multi-state pattern where the
     `->` sits on a third state's line hides the arm completely, and if `CREATED` is
     wrapped with it the classifying/`CREATED` counts fall *together* so the alphabet
     control still balances — widening the existing `couldBeTheContainerTheEditIsAbout`
     arm by one state does exactly this; (b) unqualified enum entries drop out of the alphabet with no
     count mismatch. **The stated mechanism was wrong and the hole was real:**
     `SANDBOX_ONLY -> true` in a `when` does *not* compile on Kotlin 2.4.10 (no
     context-sensitive resolution by default, no flag enables it); what compiles in
     every version is `import mcorch.core.WorkloadState.SANDBOX_ONLY`, one IDE
     quick-fix away. Name a mechanism only after compiling it, or the catch is an
     UNKNOWN dressed as a finding; (c) `if (state == WorkloadState.SANDBOX_ONLY)` is not a `when` arm at
     all, and `:core/main` has **five** of them, not three
     (`DrainController` ~363, `Reconciler` ~720 and ~2073, and `LocalNode.ensureWorkload`
     twice, ~361 and ~410 — one function classifying the state at both its adoption
     and its collision path). The `else` test closes a fourth shape. A scan built
     to find omissions should be mutated with the shapes a *formatter* produces, not
     only the shapes an author types.
156. **Verified this round, so do not re-derive:** `mainSources()` walks the whole
     module, so file coverage is not the hole; `replacementBlocker` returns null
     whenever `pass.previous?.drain != null`, so `blocker != null` really does imply
     no drain record and both `converge` arms' routing arguments hold; no path removes
     a persistent volume (`LocalNode` never deletes the volume directory), so
     invariant 5 is structural rather than conditional; `storage?.persistent == false`
     and `storage?.bound` are the only readers of `StorageStatus` outside rendering,
     so a null storage record claims nothing and gates nothing;
     `bound = observation is Present` is the project's own definition of bound, so
     `copy(bound = true)` on an `EXITED` container is consistent rather than a lie.

157. **A read-side reconstruction keyed on a drain state must survey every producer
     of that state, not only the one the field was designed around.**
     `StatusReconstruction.reconstruct` stamps `stopDispatchedAt` from
     `enteredStateAt` whenever `state == STOPPING && stopDispatchedAt == null`, on the
     stated premise that *"a drain reaches `STOPPING` only after a stop request
     returned cleanly"*. There are **two** producers of `STOPPING`:
     `DrainController.stop` (~2669, always stamped) and the already-down branch
     (~302, `letGo.moveTo(STOPPING, now)`), which is reached whenever
     `containerIsDown` answers — `Absent`, `EXITED`, `CREATED`, or `SANDBOX_ONLY`
     with `hadContainer == false` — and which never dispatched anything. That record
     is drafted into the status (`Reconciler` ~2206 / ~1229) and persisted by the
     teardown (~2516, ~2522, ~1250, ~1253), so a **current** build routinely writes
     the exact document the reconstruction claims only an older build could. The
     stamp is harmless today only because `stopIsInFlight`'s observation gate
     independently answers false for every state that branch can have been reached
     from — i.e. the safety comes from a different argument than the documented one.
     **Ask of any decode-time inference: which code paths write the antecedent, and
     did the author enumerate them or assume them?**

158. **A log line that asserts provenance ("the build that wrote this row") is a
     claim the code cannot check.** `SqliteStore.decodeStatus` (~634) warns that a
     field *"was not recorded by the build that wrote this observation"*, and its
     KDoc leans on the line being rare — a repeat is documented as meaning the row is
     not being written back, "a different fault and one worth seeing". Because the
     current build produces the same document shape (see 157), the line fires in
     ordinary operation on every replacement drain of an already-down container, and
     the one diagnostic that would reveal a genuine write-back failure is buried in
     routine noise. Word such a line as what was observed (*"this row carries no
     dispatch record; reading it as X"*), never as who wrote it.

159. **The `DRAIN_FAILED` exclusion is right, but the reason given for it is not, and
     the wrong reason invites a widening.** `StatusReconstruction` excludes
     `DRAIN_FAILED` on the ground that *"a failed drain has no edge to a stop... the
     container is never driven down, the record is never retired"*. False for the
     population that would need reconstructing: `advanceOnce` asks
     `containerIsDown(hadContainer)` at ~268, **before** any state dispatch and for
     every state including `DRAIN_FAILED`, so a genuinely-signalled container that
     exits does drive the drain to `STOPPING`/`containerDown` and does retire the
     record. The exclusion's real justification is the same one given for
     `DEREGISTERED` — the false-positive rate, since most `DRAIN_FAILED` rows never
     dispatched a stop, and a stamp there suppresses `restoreRegistration` for the
     ordinary case. The residual is that a pre-field row which *did* dispatch and then
     aborted is never reconstructed, so a converging pass can still delete the record
     and re-admit. Keep the exclusion; fix the stated reason, or the next reader will
     widen it the moment they find a retirement edge.

160. **A documentation-only diff can reinstate a premise a previous round retired,
     and it arrives with no executable line to review.** Round 39
     (`fix/bind-stop-grace-ceiling`) added the sentence *"`awaitStopped`'s re-issue is
     reached only on the paths where the first stop returned cleanly"* to
     `Node.kt` (`StopGraceCeiling`, *The relation a re-issued stop terminates on*), to
     `DrainController.awaitStopped`'s KDoc, and to `reconciler-dev`'s project memory —
     three copies of the premise item **157** had already falsified, and it now stands
     as the justification for a cross-module constant relation. The second producer of
     `STOPPING` (`advanceOnce`'s already-down branch, `letGo.moveTo(STOPPING, now)`)
     dispatches nothing, and `DrainController`'s own comment two lines above it says so
     in bold. **Diff prose against the retired-premise list, not only against the
     code.** A `git diff` that touches no executable line still changes what the next
     author is allowed to assume.
161. **A source-text scan for a *type name* does not catch configuration through
     `copy()`.** `StopGraceGuardTest.the shipped node runs on the default CRI timeouts`
     asserts no `:core` main source names the token `CriTimeouts`, so that reading
     `CriTimeouts()` in the sibling test stands for what ships. Verified: the scan is
     green, the vacuity control (`CriClientConfig(` present) really fires on
     `LocalNode.open`, the strip survives the fully-qualified spelling, and `:core` is
     the only module with a `:cri` dependency — so the module scope is right. The hole
     is that both `CriClientConfig` and `CriTimeouts` are `data class`es, so
     `cfg.copy(timeouts = cfg.timeouts.copy(stopDeadlineCap = 1.hours))` configures the
     cap while naming no `CriTimeouts` token. **Score a token scan against the
     *shapes that reach the field*, not the shapes that name the type.**
162. **"The far side is a `:cri` type, so it cannot be a `require`" is only true of the
     seam's own file.** `LocalNode.open` builds the `CriClientConfig` and is the one
     class in `:core` the seam already permits to name CRI types, so a `require`
     comparing `StopGraceCeiling.ceilingFor(SpecBounds.MAX_SAVE_TIMEOUT)` against
     `config.timeouts.stopDeadlineCap` would bind the value the process actually runs
     on — closing 161's hole and making the default-reading test unnecessary. When a
     check is declined on the seam, ask which *other* class the seam already sanctions
     before accepting a test as the enforcement point.
163. **The re-issue's dead band starts at `stopDeadlineCap + deadlineSlack`, not at the
     cap.** The deadline is `min(grace, cap) + slack`, so for `cap < grace <= cap+slack`
     the runtime's kill still fires before the call gives up and the stop completes.
     `Node.kt`'s *"One second of movement — MAX up, or the cap down — makes it live"*
     and `:cri`'s matching sentence are both overstated by one `deadlineSlack` (30s
     shipped). Direction is safe — the guard asserts `MAX <= cap`, which is exactly
     right at `slack == 0` and conservative otherwise — but an operator diagnosing a
     stuck drain from these sentences will look for the wrong boundary.

## Round 40: the require that is a build assertion, and the sweep that missed its siblings

164. **A `require` whose operands are *both* compile-time constants is a build
     assertion wearing a runtime type, and that is what puts it on the planner side.**
     `LocalNode.open`'s pre-flight compares
     `StopGraceCeiling.ceilingFor(SpecBounds.MAX_SAVE_TIMEOUT)` against
     `CriClientConfig(endpoint = …).timeouts.stopDeadlineCap` — `LocalNodeConfig`
     carries no timeouts, so nothing an operator writes, and no stored row, can reach
     the predicate. It can only be falsified by editing a constant, which reddens the
     suite first. Score every new `require` this way before applying the round-24
     objection: ask which operands are reachable from a definition or the environment,
     and if the answer is none, the "freezes a server nobody can retire" argument does
     not apply. Blast radius when it fires: `Orchestrator.open` throws before any node
     exists, the store is closed by its own catch, containers keep running unmanaged
     and nothing stops — the loud, safe direction. The residual is diagnosability:
     `main` catches `IllegalArgumentException` around `OrchestratorConfig`/`ApiConfig`
     and logs `cannot start: {}` with `EXIT_MISCONFIGURED`, but `Orchestrator.open` is
     *not* wrapped, so a wiring `require`'s message — which is the whole remedy —
     surfaces as an uncaught stack trace on the default exit code.
165. **A correction sweeps the copies a grep for the *new* wording finds, not the
     copies the *old* claim has.** The retired premise "a first stop returning cleanly
     is the only thing that puts a drain in `STOPPING`" entered at `7f43649` in four
     places at once. Round 40 corrected one of them plus its own two new copies and
     left three standing: `DrainStatus.stopDispatchedAt`'s KDoc in `:schema` (the
     field's canonical justification, and the field is the discriminator), the inline
     comment inside `awaitStopped`'s own `catch` twelve lines from the decision, and
     `ProxyDrainTest`'s KDoc. When correcting a premise, `git log -S` the *old*
     sentence and fix every site that commit created — a sweep that starts from the
     file being edited finds one.
166. **The restatement of the boundary a round exists to correct is usually in the
     same diff.** `Node.kt:437` (added) says the re-issue "finishes nothing" past the
     cap and forwards the reader to the section below; `Node.kt:476` (added) says the
     flip is at `cap + slack` and "**not at the cap**". Both new, forty lines apart.
     Re-read the diff for the *old* claim before accepting that a boundary correction
     landed; authors write the habitual sentence in the same commit as the fix. (Also
     still at the bare cap and unhedged: `StopGraceCeiling.MAX`'s KDoc, the pre-flight
     `require`'s message, and `StopGraceGuardTest`'s "above the cap … by construction".)
167. **Widening a token scan from the type name to the field name exempts the one
     file that legitimately reads the field — which is the only file that can
     introduce the hole the widening was for.** Item 161's `copy` shape is now
     invisible in `LocalNode.kt`, because naming `stopDeadlineCap` in the `require`
     puts that file permanently in the "wiring" class. The compensating control is the
     `require`, and a `require` enforces only while it reads the **same expression**
     that is passed on: `require(… criConfig.timeouts.stopDeadlineCap)` beside
     `CriClient.connect(criConfig)` is sound; `connect(criConfig.copy(timeouts = …))`
     would pass the check and run on another cap. Check the identity of the value, not
     the presence of the check.
168. **A comment stripper fails open, and only one of its two failures is loud.** The
     `codeLinesOf` depth tracker is correct on the cases it was written for (verified
     by replica: `/* */` on one line, nesting, `*/` and `/*` inside blanked string
     literals, `/*` after a `//`). An unmatched `*/` in prose is a compile error and
     stops the build; an unmatched `/*` in prose silently blanks the **rest of the
     file**, so a scan over it goes green for code it never read. Nothing asserts the
     depth is zero at end of file (it currently is, across all 27 `:core` main
     sources), and a multi-line raw string containing `/*` reaches the same state.
     Whenever a structural scan gains a stripper, ask which stripper failure is silent
     and assert against it.

## Round 41: the correction that lands everywhere but the test beside it

169. **Kotlin nests block comments, so the only *silent* way a comment stripper can
     blank a file is a multi-line raw string.** `/*` unmatched in prose raises the
     nesting depth, the file's own `*/` brings it back to 1 rather than 0, and the
     file ends inside a comment — a compile error, not a silent green. `*/` unmatched
     in prose closes a KDoc early and compiles the rest of the sentence — also a
     compile error. The case that compiles *and* fools a per-line
     `"([^"\\]|\\.)*"` blanker is `/*` inside a `"""…"""` body. Correct item 168 with
     this: the depth-at-EOF assertion is still the right control, but a comment that
     tells a reader "a KDoc line mentioning one" is a silent hazard is teaching the
     wrong failure. Check `codeLinesOf`-style depth counters cannot go negative
     (`*/` at depth 0 must fall through to code), or `depth == 0` stops being a test
     for an unmatched opener.
170. **A red-proof record is a dated measurement, and a later commit in the same
     branch can falsify it without touching the sentence.** `StopGraceGuardTest`
     records "lowering `stopDeadlineCap` to an hour reddened **this test and nothing
     else** in 954" — written at `ba0335b`, before `88999c1` added the `LocalNode.open`
     `require` and a second test that the same mutation now reddens. Forty lines
     further down the same file the later test says so in as many words. When a branch
     adds an enforcement point, re-read every red-proof paragraph that names a
     mutation on the same constant: the suite total in the sentence (954 vs 955) is
     the tell that two paragraphs were measured at different times.
171. **The premise sweep reaches production KDoc and stops at the test beside it.**
     Round 41 retired "a first stop returning cleanly is the only thing that puts a
     drain in `STOPPING`" from `Status.kt`, `DrainController`'s catch and
     `ProxyDrainTest` — and left `StatusReconstructionTest.kt:34` restating it as the
     justification for the very rule (`enteredStateAt` as the reconstructed dispatch
     instant) whose production KDoc, six files away, spends sixty lines explaining
     that the premise is false. Grep test sources for the *old* claim specifically:
     a sweep keyed on the files a change touches will not reach an assertion's
     comment.
172. **A new enforcement point is operator-facing behaviour and owes `docs/operating.md`
     a sentence.** `LocalNode.open`'s pre-flight turns "three modules must agree or the
     drain loops for ever" into "the process refuses to start with exit 78". §3 of
     `operating.md` still describes only the for-ever loop, which the shipped
     single-host path can no longer reach. Whenever a round converts a latent
     for-ever-loop into a startup refusal, check whether the file a non-contributor
     reads still describes the old outcome as reachable.
173. **`exitProcess` in a `catch` is a stop path worth auditing, and this one is
     clean.** `main` now wraps `Orchestrator.open` in the misconfiguration channel.
     What made it safe was not the catch but `Orchestrator.open`'s own two
     `catch (Throwable)` arms — `embedded.close()` after a failed `LocalNode.open`,
     `node.close()` + `embedded.close()` after a failed wiring — so the store is shut
     before the process leaves. Audit the *callee's* cleanup before accepting a
     `catch → exitProcess` at the call site; the catch itself closes nothing, and a
     store left open on a fatal path is a locked database rather than a lost world
     only because nothing has reconciled yet.
174. **A source-scanning test that counts occurrences must strip comments, or it can
     be reddened and silenced by prose.** `VelocityPinWiringTest` counts
     `Orchestrator.open(` / `fromEnvironment(` against
     `catch (invalid: IllegalArgumentException)` over the raw file text, while
     `StopGraceGuardTest.mainSources` in the same branch strips comments for exactly
     this reason. A KDoc mentioning either token shifts a count: the refusing side
     reddens spuriously, the catching side inflates and can hide a startup step that
     is genuinely outside the channel.

175. **A threshold derived "in passes" is compared against a threshold measured in
     wall-clock, and the ordering between them only holds at the backoff cap.**
     `ReconcilerConfig.drainAttentionLedger = 6` is justified by "at the 5-minute
     cap, `drainAttentionAfter` is three faulting passes, so six always trips the
     time arm first, at every cadence". The second clause does not follow: the time
     arm fires on elapsed seconds, the ledger arm on pass count, and `Backoff` starts
     a fault streak at 1s with factor 2. Six consecutive `Retry` passes elapse in
     1+2+4+8+16 ≈ 31s, so the count arm pre-empts the 15-minute arm by ~30x on a
     *continuously* failing drain — the alarm fatigue `drainAttentionAfter` exists to
     prevent. Whenever a new escalation arm is added beside an existing one, convert
     both to the same unit at the **fastest** cadence the loop can run, not the
     slowest. The test that "isolates the two arms" by advancing a fake clock 8
     minutes per pass cannot see this, because the harness sets the cadence by hand
     and the real loop sets it from `queue.failed`.

176. **A funnel inserted into `advanceOnce`'s tail sees only the passes that reach
     `step()`, and `SANDBOX_ONLY` is the early return that is *not* neutral.**
     `advanceOnce` returns before the funnel five times: no current drain,
     `containerIsDown`, non-`Present`, `UNKNOWN`, and `WorkloadState.SANDBOX_ONLY`.
     The first four establish nothing and are neutral by construction. The fifth
     `return abort(...)`s with a recorded `DRAIN_STALLED` — a genuine fault that the
     ledger never counts, while the block that follows on the next good pass still
     scores its −1. An intermittently-under-reporting runtime therefore drifts the
     ledger *down*, which is the one direction the neutral-vs-health distinction was
     written to prevent. When auditing "every pass goes through this one funnel",
     enumerate the `return`s above it and ask of each whether it *records* anything,
     not whether it *steps*.

177. **A single scalar ledger lets one subsystem's health pay down another's
     faults.** `settleLedger` credits −1 for `workDone` or for a block, and a block
     only means "the probe answered and players are on". `awaitStopped`'s block
     (players reappeared after a stop was issued) exercises neither the proxy control
     endpoint nor the save path, yet erases one endpoint fault per pass. Likewise a
     block that finds a `PERMANENT` failure standing scores −1 while nothing
     recovered. Both are inside the stated rule and neither loses data — arm 1 covers
     the permanent case — but the rule "a block is health" is only true of the
     subsystem the pass actually touched. Any future narrowing of the flapping arm
     has to say which fault a recovery is a recovery *of*.

## Round 43: the second producer of a pair the funnel keeps consistent

178. **"There is exactly one writer" is a claim about `:core`, and the decoder is
     always the second writer.** `DrainController.settleLedger` maintains
     `(faultLedger > 0) == (faultLedgerSince != null)` in one expression, and that
     really is the only assignment in the loop — but `StatusCodec.readDrain` builds
     the same pair from two independently-read keys, and the *lenient* read on one
     of them (`string(...)?.toIntOrNull() ?: 0`) manufactures the half of the
     biconditional the design did not consider: count zero beside a live instant.
     The funnel then **adopts** it (`since = observed.faultLedgerSince ?: now`
     keeps the stale instant whenever the pass faults), so the self-repair that was
     argued to only ever *delay* a report advances one instead — six faults inside
     the backoff's first 31 s against an hours-old anchor. Whenever a pair's
     invariant is defended as "maintained at one site", enumerate the *decoders*
     too, and prefer reading the pair jointly (`instant(...)?.takeIf { count > 0 }`)
     over repairing it downstream.
179. **A tolerance argued from "a refusal aborts the fleet read" is arguing from a
     premise that was retired in round 10.** `SqliteStore.readRow` catches
     non-retryable `StoreException` out of `readStatus` and charges it to one
     server (`status = null`, `unreadable` set); only an unreadable *definition*
     still fails `listServers`. So the choice between a lenient and a strict read
     of a status field is not "one server versus the fleet" — it is "this field
     reads zero" versus "`ReconcileLoop.resync` partitions this server out and its
     drain stops being reconciled until a human edits the row". State the real
     trade; the false one licenses strictness on exactly the fields that can least
     afford it.
180. **An edge-triggered log whose two sides are evaluated at the same `now`
     cannot see an edge crossed by time.** `settleLedger` logs when
     `settled.failingTooOften(now) && !observed.failingTooOften(now)`. That fires
     only when *this pass's count change* crossed the threshold. When the count
     reaches the threshold quickly — which at `Backoff`'s real first delays
     (1/2/4/8/16 s) is the normal case — and the arm raises later because the
     fifteen-minute age gate elapsed, `observed` already satisfies the predicate at
     that same `now`, so the condition goes TRUE on the dashboard with **no log
     line at all**. Item 46's family: the only non-dashboard channel is silent
     exactly where the new gate does its work. Any "log on the edge" needs the
     previous answer at the *previous* instant, or it is only a change-detector for
     the operand the pass happened to move.
181. **Adding a conjunct to an escalation multiplies its reachability analysis, and
     the prose usually keeps the old one.** Gating a net-fault count on "the ledger
     has been positive for `drainAttentionAfter`" is sound and does make the arm
     structurally incapable of firing sooner than the age arm's own delay. What it
     also does is require an *excursion* that both reaches N and survives the
     window: at the real cadence (a recovering pass reports Progressed/Waiting, so
     `WorkQueue.succeeded` puts the next delay back to 1 s) fifteen minutes is
     several hundred passes, and for a walk below one half the chance an excursion
     lasts that long decays exponentially in its length. `docs/operating.md`'s
     "hours at 40%, most of a day at 30%" was computed for the count alone and
     understates the flag by orders of magnitude. Direction is quiet, so it is a
     doc finding — but it is the sentence an operator reads to decide whether
     silence means health.

## Round 44: the verdict written on one path and read on all of them

182. **A per-pass observation drafted from a fresh record erases the last pass's
     verdict on every path that does not re-establish it.** `readControl` builds a
     brand-new `ControlEndpointStatus` each pass, so `credential` starts `UNTESTED`
     and only `assertBackends` refines it. Two proxy paths draft a status without
     ever reaching `assertBackends` — `awaitProxyReady`'s non-joinable branch
     (drafts the raw handshake record) and `drainProxy` (carries `previous.control`
     forward untouched, while `ProxySelfLink` makes the authenticated calls that
     would prove the verdict). The first *resets* a `REJECTED` to `UNTESTED` and
     flips the derived `usable` green; the second freezes an `ACCEPTED` beside a
     drain parked on the 401 that record is about. Both are exactly the "two
     surfaces disagree about one endpoint" the field was added to end, reproduced
     on the paths where the drain lives. Whenever a field is introduced as "the
     verdict of this pass's calls", enumerate the passes that make no such call and
     ask whether they *carry*, *reset*, or *freeze* it — three different answers,
     and only carry-with-a-freshness-test is right.
183. **A three-valued observation whose third value is the safe default is only
     safe while nothing branches on the derived flag.** `usable =
     reachable && compatible && credential != REJECTED` makes `UNTESTED` count as
     not-refused. Correct for this field *because* its only consumers are
     `deriveConditions`' `CONTROL_ENDPOINT_READY` and `:api`'s badge — the drain
     re-establishes the credential per call through `ControlChannel` and classifies
     a 401 as a retryable seal failure that parks, so no stop is ever authorised by
     the flag. The KDoc headline ("whether the drain protocol can be conducted
     through this endpoint") is a standing invitation to the first control-flow
     consumer, and that consumer would be proceeding on an unobserved assumption.
     Rule: a default-to-optimistic derived flag must be typed or documented as a
     *presentation* predicate; any gate must require the positive verdict
     (`ACCEPTED`), never `!= REJECTED`.
184. **The remedy a message names has to work for both ways the fault is reached.**
     A rotated control token is repairable by putting the old value back — but an
     operator who rotated *deliberately* (leak response) must recreate the proxy
     container, and the only orchestrator-driven recreate is a spec-hash change,
     which starts a proxy replacement drain whose step 2 seal goes through the very
     channel that is 401ing. So the documented remedy covers the accidental case
     and the obvious alternative is a drain that cannot complete. Safe direction
     (players keep playing, nothing stops, the edit is revertible) but it is the
     shape that ends with somebody reaching for `crictl` on the front door. For
     every operator-facing remedy, ask which *cause* of the fault it assumes.
185. **The proxy status write-skip does not exist, and never did.**
     `writeProxyStatus` compares `status.copy(observedAt = previous.observedAt) ==
     previous`, while a running proxy's draft carries `control.lastContactAt`,
     `backends.observedAt` and `players.observedAt` all set to `pass.now`. Every
     pass writes. Do not accept "an unchanged pass does not rewrite the status" as
     an argument for a proxy-side field's cost or safety — check the three moving
     timestamps first. (`credential` itself is properly in `equals` and `usable`
     properly out of it, being a body `val`.)
186. **Still open, confirmed round 44:** `readControl`'s `ControlOutcome.Unavailable`
     branch flattens *never attempted*, *no answer* and *answered unreadably* into
     `reachable = false, compatible = false`. The malformed-body case is the wrong
     one — `ControlChannel` builds `Unavailable(retryable = false)` for a body this
     build cannot parse, i.e. an endpoint that demonstrably answered — and the
     condition's `!reachable` arm outranks the `!compatible` arm, so the message
     says "did not answer" and suppresses the only remedy that applies (upgrade the
     image). `ControlCredential` does not touch this: all three cases stop the pass
     before any authenticated call, so all three are honestly `UNTESTED`. What it
     needs is a reason enum on the non-contact, not a credential verdict. Do not
     mark it closed by the credential change.

## Round 45: where the "same container?" question is answerable

187. **A carry-forward guarded on container identity is dead code anywhere except
     the pass that creates the container.** The create pass writes the new
     container id into `runtime`, so every later pass compares the new id against
     itself and the guard never fires. Round 44 prescribed the gate inside
     `readControl`; it would have been inert there, and a mutation forcing it off
     stayed green. The only sites where `previous.runtime.containerId` still names
     the *old* container are the two `node.ensureWorkload` call sites in
     `convergeProxy` (`Absent` and `SANDBOX_ONLY`). When asking "is this record
     about the container in front of me", first ask which pass still holds the
     predecessor's id.
188. **The proxy replacement path never reaches its own teardown's clear.**
     `teardownProxy`'s `control = null` / `runtime = null` block is only reached
     with the workload observed `Absent` *and* a drain cause still standing —
     which for a `REPLACEMENT` never happens, because after the removal the next
     pass sees `Absent`, `outstandingStopCause` answers null (it needs a `Present`
     observation) and `convergeProxy` builds the successor immediately. So every
     per-container record must be cleared at the *create*, not at the teardown.
     The same is true of the drain record, which is why `clearedDrainRecord` sits
     at both create sites.
189. **`recorded == null` is not the same silence as `observed == null`.**
     `teardownProxy`'s partial-removal branch deliberately writes
     `runtime.containerId = null` to record *"this loop removed the container, the
     sandbox survived"*. A create-time guard of the shape `if (observed != null &&
     recorded != null && observed != recorded) clear()` therefore **keeps** the
     dead container's record on exactly that path — the sandbox survives, the
     `SANDBOX_ONLY` create runs, and the successor inherits its predecessor's
     record. `identity()` never nulls the id by accident (it falls back to the
     previous one), so a null `recorded` is always a positive statement that no
     container is known. Clear on it.
190. **Scans that key on a receiver spelling.** `\bchannel\.[A-Za-z]+\(` catches
     `channel.state()` and misses `pass.channel(node, h).state()` or any renamed
     local. And a funnel scan scoped to one function does not cover the *other*
     funnel: `ProxySelfLink.note` is a second, unscanned collection point, so a
     second control call added to the proxy's own drain link drops its verdict
     silently. When a rule has two enforcement points, the scan has to name both.
191. **A reader-list scan that collects file *names* over a subset of modules.**
     `usable`'s consumer list is pinned to `ServerJson.kt` + `StatusDrafting.kt`
     by walking `:core` and `:api` only and comparing `File.name`. A gate in
     `:store`, `:app` or `:schema`, or a second file with either name, passes. The
     vacuity controls (file counts, positive and negative matcher probes,
     `require(root.isDirectory)`) are genuine — the gap is scope, not vacuity.
192. **Round 44's own W5 was wrong and the code is right: an empty proxy with a
     refused credential *is* replaceable.** `sealIsPrecondition(router, reading)`
     = `router != null || reading !is Empty`, `ProxyDrainSubject.router` is a
     `get() = null`, and a proxy has no transfer and no deregister step, so on a
     fresh zero-player reading the seal is waived and the replacement runs to the
     stop. The park-for-ever framing holds only with players connected. Do not
     repeat "a proxy that cannot seal can never be replaced" without checking the
     player reading.
193. **The justification comment whose *premise* a change falsifies while its
     *conclusion* stays true.** `PaperServerAgent.contractOf` defends
     `holdsWorldData = worldData ?: true` with "the last observed storage status
     is *computed from* the definition every pass, so it agrees with the edit" —
     a reason that stops being true the moment `StorageStatus` becomes observed.
     The safe default is still right, but the stated reason now reads as an
     invitation: the next reader sees a newly trustworthy second source and wires
     the drain's fallback to `previous.storage.persistent`, moving invariant 2's
     safe side off a literal `true` and onto a nullable runtime record. When a
     change makes a field observed, grep every comment that argued *against*
     consulting it — the argument, not the field, is what has to be re-derived.
194. **A safe default protected by unasserted conjunctions.** *(Closed round 47
     — `observe` now answers `mine?.labels ?: sandboxLabels`. Kept because the
     shape recurs.)* `WorkloadView.observe` merged `sandboxLabels +
     containerLabels`, so a key the container lacked silently fell through to the
     sandbox's value. `labelsDescribeItsContainer` discriminates by *state*, not
     by which map a key came from, so at `CREATED`/`RUNNING`/`EXITED` that
     fall-through sat inside the trusted branch. It was harmless only because
     three separate facts held: both maps are written from one `WorkloadSpec` in
     one `ensureWorkload` call, sandbox adoption is gated on the spec hash so a
     persistent sandbox never receives an ephemeral container, and every build has
     always written `WORLD_DATA` on the container. None of the three was asserted
     anywhere. Ask for the fall-through direction whenever a label gate is scoped
     by state. `Labels.SPEC_HASH` still falls through and should: it does so in
     its own one-key expression, which is the shape a per-key safety decision has
     to have.
195. **A carried observation whose window closes at `CREATED`, not at the create.**
     A record carried across a teardown/create gap survives only until the
     replacement container reaches `CREATED` — at which point its own label is a
     legitimate observation and overwrites the memory. So "there is a record to
     ask" is true for a guard placed strictly before `node.ensureWorkload` and
     false for one placed anywhere later, including one pass later. When a design
     defers a guard on the strength of a record existing, pin *where in the pass*
     that record still exists.

196. **Auditing the *removal* of a fallback is a different job from auditing the
     fallback.** The question is not "was the old value ever wrong" but "for every
     consumer, what does the new `null` mean, and does it land on the safe side".
     Round 47's `sandboxLabels + mine.labels` → `mine?.labels ?: sandboxLabels`
     moved three keys from possibly-answered-by-the-sandbox to absent, and the
     three consumer families answer absence in opposite directions:
     *safe-defaulting readers* (`holdsWorldData = worldData ?: true`,
     `saveConfirmable ?: declaresSaveChannel`, `storageStatus`'s carry-forward)
     get **stricter** — a stop now demands a confirmed save where it might not
     have; a *positive-label guard* (`Reconciler.forbiddenTransition`, which
     refuses a persistent→ephemeral edit only on `WORLD_DATA == true`) **loses
     coverage**, because absence is its pass-through. One edit therefore moves
     safety in both directions at once, and only the second direction needs
     arguing. Here it was acceptable — the un-refused edit still drains under
     `holdsWorldData = true`, and nothing in this repo ever deletes a volume — but
     the asymmetry is the thing to look for: enumerate readers by *how they treat
     absence*, not by which key they read.
197. **A predicate's KDoc that describes the producer it gates.** Round 47 left
     `Reconciler.labelsDescribeItsContainer` saying "`WorkloadView.observe` lays a
     container's own labels **over** the sandbox's" one commit after `observe`
     stopped doing that. The predicate stayed correct; the sentence a future
     reader consults to answer "can a key here be the sandbox's?" now answers yes
     when the code answers no — which is an invitation to restore the merge or to
     lean on a sandbox backstop that is gone. When a producer's shape changes,
     grep for the *consumers' notes that describe the producer*, not only the
     producer's own.
198. **A new label written on the sandbox only is now invisible.** Since
     `Present.labels` is the container's map alone whenever a container exists,
     any future sandbox-scoped label (a network or port fact, say) reads as absent
     in exactly the state a consumer trusts most. The fix is not to restore the
     merge: it is a per-key expression next to `SPEC_HASH`'s. Check any new
     `Labels.*` constant for which object it is written on and how `observe`
     surfaces it.
199. **A gate that starts making node calls becomes clearable by node failures.**
     Round 48's exemption let a gated `reconcilePaper` pass reach `place` and
     `node.observe`. Those throw `NodeException` on any containerd hiccup, the
     catch runs `nodeFailure`, and `recordFailure` *replaces* the top-level
     failure class — which is the one input `isBlockedByPermanentFailure` reads —
     with `RETRYABLE`, while `Pass.draft` keeps the `DRAIN_FAILED` record beside
     it. So the next pass is ungated and resumes the drain on a live, populated
     server. Nothing stops (the protocol still demands a confirmed save), but
     "permanent means stop trying" now expires at the next node restart. **Rule:
     whenever a change lets a gated pass touch the node, check every catch on the
     way out for one that writes the field the gate reads.** The safe shape is to
     re-assert the gate's own outcome when the pass's own observation failed.
200. **"Not `RUNNING`" is not "not serving".** Of the five `WorkloadState`s only
     `Absent`, `EXITED` and `CREATED` are provably empty. `UNKNOWN` is "the
     runtime reported something this build does not recognise" and `SANDBOX_ONLY`
     with `hadContainer` is "the runtime is failing to enumerate a container that
     may still have players" — the exact state `containerIsDown` and
     `stopIsInFlight` each split on `hadContainer`, and the one `WorkloadView`
     warns kills live servers. A predicate written `state == RUNNING` therefore
     lifts a guard over the two ambiguous states as well, and it will read as
     correct because the enumeration in its own KDoc quietly omits `UNKNOWN`.
     Today such a lift is inert only because both downstream arms (`advanceOnce`'s
     UNKNOWN early return, `converge`'s UNKNOWN arm) happen to do nothing — safety
     resting on two unrelated coincidences rather than on the cut. Write these
     cuts as "provably not serving" (allow-list `Absent`/`EXITED`/`CREATED`),
     never as "not running".
201. **A policy predicate one branch now contradicts, held harmless only by
     wiring.** `permanentFailureStopsPasses()` still answers *"no pass will look
     at this server again"* after round 48 made that false for a Paper server
     whose container is not running. It is harmless because its single
     behavioural consumer — `abort`'s `releaseSeal` — returns false the moment
     `subject.router != null`, and `Reconciler.drain` builds a Paper subject with
     `seal = link, router = link` from one value. **Tripwire: the day a Paper
     subject can carry a seal without a router (a standalone server that seals
     itself), the answer becomes a live lie on the kind that holds worlds — the
     twenty-seventh audit's critical, moved.** When a gate gains an exemption on
     one branch, grep every predicate that *reports* that gate's answer to
     another component, and re-derive the exemption there or record why the
     consumer cannot see it.

## Round 49: the escape hatch built beside the drain, not inside it

202. **Refusing to weaken a gate, by writing a second path, drops the whole chain
     the gate was the last link of.** `NodeForcedTermination` was written outside
     `DrainController` on the argument that weakening `mayStop` under a flag would
     corrupt a gate every stop depends on. That argument is sound about `mayStop`
     and wrong about the *stop*: the drain's stop is preceded by seal, secure a
     destination, transfer, confirm zero players, confirm the save, deregister —
     and an isolated path that reimplements only "save, then stop" silently loses
     the other five. Weakening the gate under a flag would have kept them. So the
     isolation choice is only safer if the new path re-implements the chain; if it
     re-implements the last link, it is strictly worse than the design it refused.
     **Ask of every "we built it separately to keep the gate honest": which steps
     of the protocol lived *before* that gate, and where are they now?** No test
     can see their absence, because there is no shared path to diff against.
203. **"Always requested, always waited out" is falsified by the callee's
     zero-cost early returns.** `PaperServerAgent.requestSave` has three returns
     that issue no exec and consume no wall clock: `Unconfirmable` (the
     container's `SAVE_CONFIRMABLE` label reads false — the exact `operating.md`
     note-1 population an escape hatch exists for), `unbuildableSave`
     (`saveTimeout <= 0`, which `SpecBounds` deliberately does not floor and which
     its KDoc says reaches here by design), and `NotDelivered` from an RCON client
     that fails to connect in about a second (a refused password — also a
     documented note-1 arrival path). A caller whose safety story is "the save
     timeout is always spent, and that wait is what lets an in-flight save land"
     has that story only on the `Unconfirmed`-by-timeout branch. Enumerate a
     callee's returns by *cost*, not by outcome type, whenever a caller's argument
     rests on time elapsing.
204. **A clamp in `:schema` is justified by a premise about every stop in the
     system, and a new ungated stop invalidates it from another module.**
     `SpecBounds`' KDoc argues that clamping is safe because "`MAX_STOP_GRACE_PERIOD`
     bounds a stop that `mayStop` has already gated on a confirmed save, so the
     grace period there is the last-resort net and not the save path", and that
     clamping `saveTimeout` "can only withhold a confirmation, and an unconfirmed
     save is a container this orchestrator will not stop". Both sentences are
     load-bearing *behaviour* justifications, not prose, and both become false the
     moment any path stops without `mayStop`. Before adding a stop anywhere, grep
     `:schema` and `:cri` for justifications of the form "safe because the drain
     already confirmed" — they are the invariants the new path inherits without
     being told.
205. **`StopGrace.of(stopGracePeriod, saveTimeout)` means something different
     either side of `mayStop`.** Downstream of the gate the floor is a net over a
     save that already landed. Upstream of it the grace period *is* the save, and
     the floor is derived from `saveTimeout` — the very channel that just failed,
     and a field that is zero or meaningless in precisely the branches where the
     grace period is doing all the work. A guard test asserting "the pair travels
     together exactly as the reconcile path's does" checks the syntax of the call
     and cannot see that the two sites are protecting different things.
206. **A capability guard keyed on route *patterns* stays green when the
     capability arrives as a query parameter.** `RouteTableTest.the table has no
     route that could free a name or stop a container` asserts no pattern contains
     `stop`/`kill`/`force`/`purge`, reasoning "one that could stop a container
     directly could stop one with players on it". `DELETE …?force=true` does
     exactly that and the test passes untouched. The spec authorised and required
     the rewrite; the change that added the capability did not do it. Whenever a
     guard is spelled over names, ask what shape of the forbidden capability the
     spelling cannot reach — and treat a guard that stayed green through the
     change it was written to catch as a finding in itself.
207. **A destructive store write placed above the applicability check turns a
     refusal into a silent delete.** The force handler tombstones the definition
     and *then* discovers it is a `VelocityProxy` or has no running container,
     answering `409 FORCE_NOT_APPLICABLE` — "there is nothing here to force" — over
     a definition it has already marked terminating. For a proxy that is a
     fleet-wide deletion delivered under an error status. The test asserts the
     status code and never asserts the definition survived, which is the
     instrument-that-looks-like-a-result shape. For every error return in a
     mutating handler, ask what has already been written when it fires.

## Round 50: the refusals that fix a stop by making it unreachable

208. **A refusal added on a delete path inherits the tombstone that was already
     written, and `putDefinition` refuses a terminating row.** `SqliteStore`
     returns `ConflictReason.TERMINATING` for *any* write to a definition with
     `deleted_at` set, and there is no un-tombstone anywhere — `purge` is the only
     other operation and the loop reaches it only from an `Absent` observation. So
     a refusal whose message says "correct that field and force again" is advice
     the operator cannot follow the moment it fires below a `deleteDefinition`
     call: the row is frozen, the drain still cannot stop the server, and the
     escape hatch refuses on every retry. Erring safe **and** unrecoverable
     without `crictl` is item 13's rule, and a validation moved *into* the stop
     path is the newest way to reach it. Any refusal on a delete path must be
     decided above the tombstone write, or must not be a refusal.
209. **`StopGrace.of`'s second argument only ever raises a ceiling, so passing a
     "floor" to it is inert.** `StopGraceCeiling.ceilingFor` returns
     `maxOf(MAX = 2h, saveTimeout + 30s)` and `bound` only caps — it can never
     raise a requested duration, and it can never cap below two hours, which
     `SpecBounds` already caps `stopGracePeriod` at. So `StopGrace.of(declared, X)`
     produces `declared` for every `X` any definition can carry. A change that
     introduces a new floor "so `ceilingFor` cannot cap the window the world has
     left" has written a comment describing a mechanism that does not exist; the
     real protection is whatever separate `if` sits beside it. When a fix is
     expressed as an argument to an existing bounding function, evaluate the
     function at the reachable range before believing the argument does anything.
210. **A zero-player observation means something only while a seal holds it.**
     `requireEmpty`'s freshly-observed zero is durable because every state from
     `SEALED` onward re-asserts `holdSeal`, and `saveEvidenceMaxGap` (30 s) voids
     it when the chain breaks. A probe on an *unsealed* server decays the instant
     it is taken. Any new path that substitutes "probe once, then act" for
     `requireEmpty` has a window between the probe and the stop as wide as the save
     timeout plus the grace period — up to an hour — during which players join a
     server nothing is holding shut. The probe returning zero is then not a
     licence; it is a measurement with no shelf life. Ask of every occupancy check
     outside the drain: what keeps this true until the stop?
211. **An acknowledgement flag that carries no value is a checkbox, and one that
     the target population always trips is a constant.** A boolean
     `acknowledgeOccupancy` says "proceed regardless", not "I acknowledge *n*",
     so it cannot detect that the population changed between the operator's
     decision and the request — the compare-and-swap shape (acknowledge the
     count, refuse on mismatch) is the one that protects anybody. Worse, the
     population this path exists for is wedged servers, whose probe never answers,
     so the count is `null`, so the acknowledgement is *mandatory* on every real
     use and becomes a fixed string in the runbook. A confirmation that fires on
     every legitimate invocation has been designed into noise.
212. **A guard reading stored observed status treats "cannot read" as "not
     happening".** `guardAgainstDispatchedStop` is
     `(existing.status?.status as? PaperServerStatus)?.drain ?: return` — an
     unreadable status row (round 30 made those `status = null` rather than a
     fleet-wide failure) and a stop dispatched since the last status write both
     fall to the permissive side of a guard whose entire purpose is preventing a
     second stop and a second `save-all flush`. Same family as item 200: absence
     of evidence read as evidence of absence, on the one branch where the cost is
     a flush into a container already shutting down.
213. **Fixing a claim in the code does not fix the guard test that asserts the old
     claim.** `DrainWiringTest.the stop grace ceiling is applied at one site` still
     carries the reasoning *"it passes `spec.lifecycle.drain.saveTimeout` as the
     floor … so the pair travels together exactly as the reconcile path's does"*
     after the code stopped always passing `saveTimeout`. The test is green because
     it asserts the enclosing function name, not the argument. When a finding is
     addressed by changing what a call site passes, re-read every guard whose
     comment describes what it used to pass.

## Round 51: the side effects the system has no memory of

214. **A stop dispatched outside the reconcile loop stamps nothing, so every
     mechanism built to make a dispatched stop safe is blind to it.**
     `DrainStatus.stopDispatchedAt` is what `stopIsInFlight` answers on, and that
     predicate's own KDoc names the critical it exists for: *"or the loop
     converges over the top of its own stop and a proxy re-admits players to a
     process whose shutdown save has run."* `NodeForcedTermination` holds only a
     `NodeRegistry` — no store, no status — so its `stopWorkload` leaves no trace
     but a log line. For the whole grace period the loop sees a `RUNNING`
     container under a terminating definition, starts a drain from scratch over a
     process already running its shutdown save, and only `requireEmpty`'s probe
     accidentally keeps step 5 from putting a second `save-all flush` into it.
     Whenever a component outside the loop performs an action the loop records
     when *it* performs it, the missing record is the finding — not the action.
215. **A compare-and-swap against a quantity nothing holds still can livelock, and
     each turn costs a side effect.** Replacing a boolean acknowledgement with a
     counted one (`acknowledgeOccupancy=12`, refuse on mismatch) is the right
     shape and is unimplementable without the thing that freezes the number. With
     no proxy seal, the population moves between the refusal and the operator's
     re-send, so the CAS can refuse indefinitely on a busy server — and because
     the deciding probe sits *after* `requestSave`, every attempt spends a whole
     `saveTimeout` and delivers another `save-all flush`. A CAS is only a CAS if
     something owns the value between read and write; on an unsealed server
     nothing does. This is the concrete argument that the seal is a blocker rather
     than a follow-up, and it is stronger than the abstract "the probe borrows
     credibility from the seal" version.
216. **"The binding protection is the seam's own" has to name which observation
     binds it.** The forced path's `guardAgainstDispatchedStop` lives in `:api`,
     and its KDoc excuses being advisory on the ground that *"the binding
     protection against a second save is the seam's own, which observes the
     container rather than a status row"*. The seam observes `WorkloadState`,
     which says `RUNNING` — it says nothing about whether a save is outstanding.
     There is no binding protection; the sentence invents one. When a guard is
     documented as advisory-because-something-else-is-binding, go and read the
     something else.
217. **Raising a grace period is right and is not free, and the cost is not the
     wait.** `maxOf(declared, SHUTDOWN_SAVE_ALLOWANCE)` on the no-save branch is
     correct — a server that finishes early exits early, so the extra ceiling
     costs wall clock only for a process that ignores `SIGTERM`. What it does cost
     is *window*: every hazard that lives between the dispatch and the container's
     exit gets multiplied by the same factor. Before accepting "raising a timeout
     is free", enumerate what is unguarded during the interval, not what is waited
     for.
218. **A `preflight`/`act` split is honest only where `act` re-runs the check, and
     the residue is what `act` cannot re-derive.** Splitting refusals into a
     pre-write phase genuinely fixes the tombstone-strands-the-row problem when
     `act` repeats the checks (occupancy) or degrades safely without them (an
     unbuildable `saveTimeout` falling through to a raised grace period). The
     order-dependence that survives is whatever only the *caller* can see —
     here the drain status, which `:core`'s seam is not given. "I could not make
     it binding without `:core` writing desired state" is usually answerable:
     reading observed state is not writing desired state, and the field in
     question is one `:core` already owns.

## Round 52: two writers of observed state, neither of them compare-and-swapping

219. **"The loop is the only writer of observed state" is a premise, not a
     comment, and the second writer invalidates three things at once.**
     `SqliteStore.putStatus` says it deliberately does not append to the change
     feed *because* the loop is the only writer — so a status written from
     anywhere else never reaches the SSE stream. Worse, neither writer uses a
     precondition: `Reconciler` passes `observedDefinition` but no
     `Precondition`, and a new caller passing `Precondition.None` performs a
     read-modify-write with no CAS. The two clobber each other in **both**
     directions — an outside write can erase the drain's `saveRequestedAt` (the
     never-re-send wedge, item 6) or `sealRequestedAt` (item 32), and the loop's
     next pass, building its status from a snapshot read before the outside write,
     erases the outside record. When a second writer is introduced to a field the
     loop owns, grep the store for sentences justifying anything *by* the single
     writer, and give both sides `Precondition.AtVersion`.
220. **A stamp written before the side effect locks the retry out when the side
     effect fails.** `stopDispatchedAt` is deliberately written before
     `stopWorkload` — right, and the field's KDoc argues the asymmetry. But a
     guard that refuses a *new* force while `stopDispatchedAt != null` then turns a
     transient `NodeException` from that same call into a permanent lockout: the
     error says "Nothing was stopped", the record says a stop is in flight, the
     definition is already tombstoned so nothing can be edited, and the drain
     cannot finish for the population the path exists for. `awaitStopped` has the
     licence this needs — it re-issues a stop that did not take — so a refusal
     keyed on the stamp must be bounded by the grace period, not by the stamp's
     existence.
221. **`null` is not zero, and a bypass written `== 0` therefore excludes exactly
     the population the feature is for.** `refuseUnsealedPopulation` lets an
     *empty* server through when the seal fails, on the drain's own
     `sealIsPrecondition` trade. A wedged server does not answer a probe, so its
     count is `null`, so it is never "empty" — and a wedged backend behind an
     unreachable proxy is refused for ever, below the tombstone, with both stated
     remedies ("fix the proxy, or wait for it to empty") outside the caller's
     reach. Whenever a refusal has an escape hatch keyed on a count, evaluate the
     hatch at `null` before believing the refusal is recoverable.
222. **A three-valued seal result whose "nothing to seal" arm has two producers
     inverts the safety ordering.** `Standalone` (no proxy routes here) and
     `Conflicted` (two proxies claim it and both are admitting) collapse into one
     `NOTHING_TO_SEAL`, so the populated-and-unsealable refusal never fires for
     `Conflicted` — the case with *two* open doors is treated more permissively
     than the case with one door that would not shut. When an enum arm is reached
     by an `else ->` over a sealed hierarchy, name the members rather than the
     remainder, and check that each one satisfies the arm's own KDoc sentence.
223. **A drain step re-implemented outside the loop inherits the loop's
     re-assertion, and `DRAIN_FAILED` declines to re-assert.** "The seal I left
     behind is fine because the drain re-asserts it" holds only while the drain is
     advancing. For a permanently parked drain — the population an escape hatch
     exists for — `DRAIN_FAILED` deliberately does not re-assert, and
     `assertBackends` is unreachable because every pass on a terminating
     definition drains. The seal then survives as unpersisted proxy state until
     the proxy restarts (item 46). Not a harm, but the justification is false, and
     a false justification beside a correct decision is what this project keeps
     having to unpick.

## Round 53: the fix that answered one direction of a two-direction race

224. **A refusal keyed on a wedge field is unbounded exactly where the wedge is
     permanent.** `refuseSecondSideEffect` refuses a force while
     `drain.saveRequestedAt != null`, telling the operator to *"wait for it to
     confirm or fail"*. `DrainStatus`' own KDoc says that field is *"the wedge that
     stops a second `save-all flush` reaching a live server, and **only a human, or
     a pass that has observed a player**, may clear it"* — and `save()`'s
     `Unconfirmed` arm sets it alongside a `PERMANENT` `DRAIN_SAVE_TIMEOUT`. That
     pair *is* `operating.md` note 1. A wedged server never answers a probe, so no
     pass ever observes a player, so nothing ever clears it: the escape hatch
     refuses the state it was built for, permanently, below a tombstone. The sibling
     branch keyed on `stopDispatchedAt` was bounded by the grace window for exactly
     this reason and this one was not. Whenever a guard is keyed on a field, read
     that field's *clearing* rule before believing the guard's remedy is reachable.
     **Corrected from a wrong version of this item**: the original claimed
     `recordStopDispatched` wrote without a precondition. It does not — `08f62fb`
     added `Precondition.AtVersion` with a re-read-and-retry-once on both writes.
     See [[audit-process-verify-before-citing]].
225. **A `?: storedValue` fallback silently outranks whatever site deliberately
     wrote null.** `preservingDispatch` takes the stored drain wholesale when the
     draft has none, which suppresses `clearedDrainRecord` — the one rule for
     retiring a drain record, whose own KDoc documents the downstream effect
     (`ProxyPass.backends` re-derives `sealed` and `letGo` from the record, so a
     vanished record un-seals and re-registers, level-triggered). Unreachable today
     only because both `clearedDrainRecord` sites sit on paths a terminating
     definition never takes. Before adding a "the draft's null must be staleness"
     fallback, enumerate the sites that write that null on purpose and what
     downstream re-derives from its absence.
226. **A gate written as a performance optimisation can be the only thing holding
     a correctness property, and only the performance reason gets documented.**
     `preservingDispatch`'s `if (!terminating) return` is justified as "no extra
     read on the steady-state path". It is *also* what keeps the function away from
     `clearedDrainRecord` (item 225) and away from the `CREATED` observation whose
     surviving record once "made the next pass drain the replacement that had just
     been built, for ever". Widen the gate for a good reason — a second force path,
     or someone deciding the read is cheap — and two unrelated properties break
     with no test to say so. When a cheap guard has a cheap justification, ask what
     else it happens to exclude.
227. **A guard that enumerates retirements cannot see a resurrection.**
     `every drain record this loop retires is retired through the one rule` scans
     for `drain = <value>` assignments and was cited as having caught the new code
     "on the way in". It caught the *addition*; it is structurally incapable of
     catching the hazard, because `preservingDispatch` never retires a record — it
     un-retires one. A guard's scan predicate is its blast radius; when a change
     introduces the inverse of the operation a guard enumerates, the guard's green
     is evidence about the old operation only.
228. **Confirm a discrimination test by reverting the fix, not by reasoning about
     the hook.** `StopDispatchDurabilityTest` depends on `TestStore.afterNextRead`
     firing on the *pass's* snapshot read rather than on `preservingDispatch`'s own
     re-read — the hook self-clears, so which read it lands on is load-bearing and
     undocumented. Reverting the four call sites in a scratch worktree and running
     the test took two minutes and turned "I believe it would be green against the
     broken code" into a fact: it **fails**. Do this whenever a test's value rests
     on an interleaving hook; the reasoning is exactly as reliable as the reasoning
     that put the bug there.
229. **A set-once field enforced at every call site belongs in the store's
     transaction instead.** `stopDispatchedAt` is documented "set once, never
     un-stamped", and enforcing that in `:core` produced an enumeration of write
     sites, a `terminating` gate, an extra non-atomic read, and a residual window
     nobody closed. `SqliteStore.putStatus` already runs `readStatusRow(connection,
     name)` inside its own `write { }` transaction — preserving a non-null stamp
     there is atomic by construction and covers every present and future writer.
     The tension to state rather than skip: CLAUDE.md says policy in the store is
     policy in two places. A field-level invariant the field's own KDoc declares is
     not policy, and `:store` already knows `drain_state`.
