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
