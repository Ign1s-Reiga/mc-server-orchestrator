---
name: standalone-paper-drain-shape
description: How the drain protocol is deliberately reshaped for a standalone Paper server with no proxy, and which failure postures the human chose on purpose
metadata:
  type: project
---

A standalone Paper server has no Velocity proxy, so drain steps 2 (seal), 4
(transfer) and 6 (deregister) have no counterparty. The decided shape, as of the
`feat/paper-server-kind` review (2026-07-26):

- The states are still traversed and recorded, with empty bodies, so the state
  machine and the dashboard stay whole and adding a proxy later fills in bodies
  rather than reshaping the flow.
- **Players online means the drain aborts**, retryably, container untouched.
  Zero players is the only path to a stop. Nobody is ever kicked.
- A persistent server with RCON disabled cannot be drained at all, and therefore
  cannot be deleted, because save confirmation needs a reply and only RCON
  replies. "Undeletable" was chosen over "stop without confirming".
- A requested-but-unconfirmed save is a permanent abort and is never re-sent; the
  unwedging path is an operator editing the definition.
- **The drain reads the container's labels, not the definition** (`Labels.WORLD_DATA`,
  `Labels.SAVE_CONFIRMABLE` → `WorkloadContract`). Accepted at review over the two
  alternatives — refusing the replacement edit, or an operator acknowledgement —
  because it generalises to any edit combination and because an acknowledgement
  would be the project's first mechanism for skipping a save, and mechanisms that
  skip a save get reached for. `Labels.WORLD_DATA` absent means *unknown*, and the
  drain answers unknown with `true`.
- **The two storage guards answer different questions and are allowed to differ
  on `null`.** Settled at the fourth audit. `Reconciler.forbiddenTransition` asks
  "is this edit a transition away from persistent storage" and refuses only on a
  *positive* `WORLD_DATA` label, because on an unlabelled workload a transition is
  indistinguishable from a lobby that was always ephemeral, and refusing made
  those permanently unreplaceable with advice that did not apply. The drain asks
  "might this container hold a world" and answers unknown with yes. Net effect:
  an unlabelled workload edited to ephemeral is *applied, with a confirmed save
  first*. Ruled sound because nothing ever deletes a volume directory — the edit
  unmounts the world, and reverting `storage.mode` remounts the same path. Do not
  re-propose refusing on `null` here.
- **Escalation is not a retry limit.** `failure-modes.md` item 7 forbids changing
  what happens to the *container* at a limit. It does not forbid changing what the
  drain *says*. A counter that raises a needs-attention signal while the container
  keeps running and the retries continue is permitted and wanted; give up, force
  stop, kick or shorten the grace period and it is item 7.
- **A save confirmation must be backed by an unbroken chain of positive
  zero-player observations**, not merely by "no player was seen since". Two
  mechanisms, accepted at the third audit: every `requireEmpty` branch that
  cannot confirm zero players voids the evidence, and a gap between the last
  recorded observation and now (`ReconcilerConfig.saveEvidenceMaxGap`, 30s) voids
  it too. Erring short only costs an extra `save-all flush` on an empty server.
- **Unknown container start time falls back to requiring a *fresh* confirmation**,
  not to re-saving. Re-saving there never terminates: the confirmation taken this
  pass has no start time to beat either, so the drain saves, refuses to stop and
  saves again against a live server for ever. Verified as real — do not re-propose
  the "unknown startedAt → save again" fix.

- **A probe that cannot run aborts the drain; the save is not attempted.** Ruled
  at the fifth audit against `failure-modes.md`'s "agent responds but the server
  does not → attempt the save and wait the full grace period". That row assumes
  an in-container agent separate from the game server. Here the two channels are
  `mc-monitor` (SLP) and `rcon-cli` (RCON), and RCON needs the same main thread a
  frozen server is not running — so a server that cannot answer SLP cannot
  confirm a save either. The row's real trigger in this codebase is *SLP answers,
  RCON does not*, and that case **is** implemented: `requireEmpty` passes on
  `Joinable(0)`, `requestSave` runs the full `saveTimeout`, and the unconfirmed
  result is a permanent abort with the container left running. Attempting the
  save on a *failed probe* instead would fire `save-all flush` at a server whose
  occupancy is unknown, and on the real trigger for this — a Paper server taking
  60-95s to generate its world — it would time out and wedge the drain
  permanently, making a server deleted during world generation undeletable. Code
  is right; the skill doc needs the actor mapping spelled out, not a change of
  rule. Do not re-propose "attempt the save anyway".

- **Two cancellation windows stay open on purpose (round 7).** (1) A cancellation
  *inside* the save exec can leave `save-all flush` delivered with nothing
  recorded; the next process re-probes, finds zero players, and sends a second
  one. Accepted: the repeat is idempotent, lands on a server just confirmed
  empty, and is still followed by a fresh confirmation before any stop. Neither
  shielding the exec (a shutdown that waits out `drain.saveTimeout`) nor
  write-ahead is an improvement. (2) Losing the teardown's `containerId = null`
  record strands a sandbox and a store row, retryably and loudly, with the
  container already gone. Both upheld as open; do not re-propose a wider
  `NonCancellable`.

- **A permanent drain failure raises `NEEDS_ATTENTION` immediately (round 8).**
  It used to require `RETRYABLE` and `drainAttentionAfter`, which left the states
  whose documented remedy is "a human resolves this" — unconfirmable save,
  `DRAIN_SAVE_TIMEOUT`, no save channel — as the only ones never flagged. Two
  things make immediacy forced rather than preferred: the gate writes no status
  for a non-terminating server, so a timer would never be re-evaluated (see
  [[drain-audit-danger-patterns]] item 26), and `:api` ranks `TERMINATING` above
  everything for its badge, so an unflagged failed drain reads as "on its way
  out" while it is still running and joinable. The flag reports; it never
  authorises, and no failure class, grace period, stop or player count moves with
  it. Nothing clears a permanent one on its own — only a delete or a definition
  edit produces a pass that can — and that is correct: a flag that expired by
  itself would stop the dashboard asking for help while the server is still
  stuck. Do not re-propose a timer here, and do not propose expiry.
- **The flag has now been wrong twice, and both times it was found from outside
  `:core`.** Both defects were about what the flag *means to a consumer*, not
  about the rule's arithmetic, and `:api` has no `:core` dependency (not even in
  tests), so a conformance test has to live in `:app` or use a shared fixture
  built by `:core`'s `draftStatus` — a hand-built conditions list in `:api` would
  be vacuous.

**Why:** these are human decisions, not accidents — do not report the missing
seal/transfer/deregister bodies as skipped steps, and do not propose a timeout
that stops anyway.

**How to apply:** audit against these postures. The interesting question is never
"did it skip a proxy step" but "does the absence of a seal let the player count
go stale, and does the permanent-abort posture create pressure toward a manual
force stop". See [[drain-audit-danger-patterns]], especially items 7 and 8.

- **`DRAIN_NO_DESTINATION` + `PERMANENT` is refused by `FailureStatus.init`, and
  the refusal stands (round 9).** Ruled against the alternative of coercing a
  contradictory stored row to `RETRYABLE` on read. Coercion would in fact be
  semantically exact — that pair has only one legal class, the stored `failure`
  gates nothing a stop depends on (`recordFailure` overwrites reason and class
  from the current pass; the load-bearing drain fields are `saveRequestedAt`,
  `worldSavedAt`, `playersEvacuated`, `state`) — but it launders a row that,
  since no code path writes the pair, was edited by hand. Refusal is right; the
  cost is bigger than it looks (see [[drain-audit-danger-patterns]] item 28), and
  the fix for the cost is per-row isolation in the store's list reads, never a
  weakening of the type. Enforcement is defensive, not load-bearing: both
  `DRAIN_NO_DESTINATION` sites in `DrainController` pass a `RETRYABLE` literal.
- **The dashboard conformance property carves out players-online, and that carve
  -out is exactly right (round 9).** *Shown TERMINATING/DRAINING, still reported
  joinable, drain given up, and nobody online ⇒ flagged.* The suspicion that it
  hides a stuck-with-players case does not land: `ready` is true only when a
  probe answered *this* pass, and the only failure the machine can record with a
  fresh positive count is the deliberately-suppressed `DRAIN_NO_DESTINATION`
  (`requireEmpty`'s and `awaitStopped`'s `online > 0` branches both write it).
  So the carve-out is coextensive with the suppression. Do not re-propose the
  stronger *progressing or flagged* form — it fails the players-online drain,
  which is correct behaviour.

- **A server whose stored observation cannot be read is held back from the queue,
  not queued-and-refused, and that is right for a reason better than the one
  given (round 10).** The implementer flagged it as trading latency for clarity.
  There is no latency to trade: the queued-and-refused variant ends in
  `Reconciler.storeOutcome` → `ReconcileOutcome.Failed` (the decode failures are
  all permanent), and `ReconcileLoop.requeue`'s `Failed` branch calls
  `queue.succeeded` and does **not** re-add — so that server also comes back only
  at the next resync. Held-back is strictly cheaper and strictly clearer. Do not
  re-open this. The residual: `ReconcileLoop.report` partitions on
  `unreadable != null` and ignores `Unreadable.retryable`, which is correct only
  while every decode failure is permanent.
- **The safety of that case is load-bearing on `Store.getServer`'s strictness, in
  another module, and it is not in the conformance suite (round 10).**
  `Reconciler.Pass.previous` is `stored.status?.status as? PaperServerStatus`, so
  a tolerant point read would silently present an unreadable observation as
  never-observed: a mid-flight drain restarts at `DRAIN_REQUESTED`, re-issues
  `save-all flush`, and the permanent `DRAIN_SAVE_TIMEOUT` wedge un-wedges itself
  — danger-pattern 14's shape, arriving through the store instead of through a
  new caller. `StoredServer.neverObserved` exists to name the distinction but has
  **zero production call sites**; the refusal is pinned only in `CorruptStoreTest`
  (SqliteStore-only). A guard in `Reconciler` is genuinely unreachable today, so
  the right hardening is a conformance hook, not a guard.
- **"The surfacing channel is itself the corrupt thing" is false for an
  unreadable observation (round 10).** The corrupt thing is the status *row*; the
  channel is `:api`, which reads the intact definition row and now receives
  `StoredServer.unreadable` on the read it already makes. So the round-8 rule
  stands unweakened — a server the loop has given up on must be surfaced, and
  here it can be, without writing anything to the corrupt row. See
  [[drain-audit-danger-patterns]] item 30 for what `:api` says instead today.
