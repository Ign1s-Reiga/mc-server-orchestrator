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
