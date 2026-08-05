---
name: drain-audit-danger-patterns
description: Reusable audit checks for stop/drain safety in this repo — the non-obvious failure shapes found in real reviews, beyond the forbidden list in failure-modes.md
metadata:
  type: project
---

Audit heuristics that go beyond the seven forbidden implementations in
`.claude/skills/drain-protocol/references/failure-modes.md`. Each was found in a
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
