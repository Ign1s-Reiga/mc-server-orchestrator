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

Related: [[standalone-paper-drain-shape]]
