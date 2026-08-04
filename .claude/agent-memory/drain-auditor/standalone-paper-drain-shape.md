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

## What the Velocity proxy kind supersedes (round 11, pre-implementation)

Reviewed against `main` at `c85e7fd` before any proxy code existed. The human
decisions above stand as decisions; what follows is which of *my* rulings were
reasoned from the absence of a proxy and do not survive it.

- **"Adding a proxy later fills in bodies rather than reshaping the flow" is
  false.** `requireEmpty` wraps `SEALED`, `TARGET_RESOLVED` and `TRANSFERRING`,
  so the three states that exist to move players abort on players being present.
  The flow does get reshaped: the zero-player gate must apply from `SAVING`
  onward only. See [[drain-audit-danger-patterns]] item 35.
- **Round 7's cancellation window splits.** It survives for a *transfer* — the
  effect is observable as the SLP count, so a repeat is benign — but only while
  nothing gates a re-issue on a stored "transfer issued" flag. It does **not**
  survive for the seal or the deregistration, and not because a repeat is
  harmful: those are the first effects that need *undoing* on abort, which
  inverts item 23's safe direction. See items 32 and 33.
- **`DRAIN_NO_DESTINATION`'s escalation exclusion does not survive.** Its premise
  ("this resolves itself when they log off") holds only for a drain with no
  transfer counterparty. Split the reason; keep the exclusion on the
  waiting-for-zero-players one.
- **`FailureStatus.init`'s `DRAIN_NO_DESTINATION ⇒ RETRYABLE` survives on the
  merits** — "no capacity anywhere" is still something the loop should keep
  trying, and `PERMANENT` only ever means "stop trying" — but its stated
  justification in the KDoc is the escalation exclusion, which does not. Re-argue
  it rather than inheriting it.
- **The round-5 actor mapping survives unchanged and gains rows.** SLP-off-Netty
  vs RCON-on-main-thread is untouched by a third actor. What is new is that the
  proxy's player count must never replace SLP for the gate (item 36), and that a
  proxy restart silently forgets an in-memory seal.

**How to apply:** when auditing proxy drain code, the first two questions are
"what does the abort path restore at the proxy" and "what re-asserts the seal
after the proxy restarts". Neither has an answer in the standalone machine.

## Round 11 rulings: blocked is not failed

Audited on `feat/velocity-proxy-kind`. No stop path was touched; the two
`stopWorkload` sites, `mayStop`, `saveIsCurrent`, `forgetSaveEvidence`,
`containerIsDown` and `teardown` are byte-identical to `main`.

- **`DrainState.DRAIN_FAILED` holding a blocked drain is accepted**, and the
  strongest argument is one the implementer did not give:
  `ReconcileLoop.RESUMABLE_DRAIN_STATES` is `DrainState.entries - DRAIN_FAILED`,
  so a new `DRAIN_BLOCKED` state would land in the *resumable* set by silent
  set-subtraction. The three consumers of the state — `draining`, the
  `DRAIN_FAILED → ServerPhase.RUNNING` mapping, and the resume branch — all want
  the same answer for a block as for an abort. The cost (`display.drainState`
  reads `DRAIN_FAILED` on a healthy wait) is only tolerable because
  `display.drainBlocked` is rendered beside it. Do not let a later change drop
  that flag and leave the state as the only discriminator.
- **No decode-time `require` for blocked/failure disjointness: upheld**
  ([[drain-audit-danger-patterns]] item 28). But with no `require`, the *precedence*
  is the whole specification, and it is not implemented at both sites — see item
  39. "The failure wins" also has to say which failure; `drain.failure` and
  `status.failure` are different fields and the pair is reachable via
  `Reconciler.nodeFailure`.
- **Retiring `FailureReason.DRAIN_AWAITING_ZERO_PLAYERS` and deleting
  `escalates()`'s reason parameter is right**, and it retires both round-9/10
  findings against that mechanism rather than moving them. `DRAIN_NO_DESTINATION`
  now has **zero production writers**; `FailureStatus.ALWAYS_RETRYABLE` and its
  `require` are purely forward-looking for the fleet-capacity case, which is the
  correct posture and should be re-argued, not inherited, when that case lands.
- **The `:app` conformance property must stay end-to-end.** `:api` cannot call
  `:core`, so `DrainBlockRenderingTest` hand-builds the condition list; the only
  thing proving `:core`'s derivation and `:api`'s reading of it agree is
  `DisplayConformanceTest`. Do not let it be moved into `:api`.

## Round 12 ruling: `NEEDS_ATTENTION` is a general flag, not a drain flag

Charter question on `feat/velocity-proxy-kind`, read-only. No stop path examined
was changed; the ruling is about reporting only.

- **The drain-only scope is an accident and should widen to `status.failure`.**
  `escalates()` is already class-based and drain-free; only the adapter
  (`DrainStatus.escalated()`) is drain-shaped. See
  [[drain-audit-danger-patterns]] items 43 and 44. `ConditionType.NEEDS_ATTENTION`'s
  own KDoc already says the name is deliberately general and a permanently failed
  bring-up is the next thing to raise it.
- **The alarm-fatigue objection is real but does not apply to the two-armed
  rule.** It only bites if `status.failure` is folded in flat. Under
  `escalates()` a retryable node blip escalates no sooner than
  `drainAttentionAfter`, and `recordFailure` supplies the anchor: `occurredAt`
  survives same-reason repeats, resets on a different reason, and `Pass.draft`'s
  `failure = null` default clears the whole thing on any successful pass. The
  widened flag is *more* self-clearing than the drain-only one and errs quiet on
  a flapping node. Do not re-propose the flat fold, and do not anchor a pass
  failure on `drain.startedAt` — that fires instantly on a long-running block.
- **`Reconciler.nodeFailure` must keep leaving `drain.failure` null.** Writing it
  would (1) abort a drain that never ran this pass, (2) destroy the `DrainBlock`
  via `abort`'s `blocked = null`, restarting `since`/`observations` on a
  transport blip, and (3) collapse the very distinction `detail()`'s precedence
  discriminates on. Node failures *inside* the drain's own steps already become
  drain failures through `abort` (`NodeOperation.EXEC`/`STOP` → `DRAIN_STALLED`).
- **`status.failure == drain.failure` on an aborted drain is structural, not
  incidental**: one expression, `Reconciler.kt` `failure = progress.drain.failure`
  in the drain branch. Three consumers now depend on it — `detail()`'s
  precedence, migration V5's decision to drop the retired top-level `failure`,
  and the copy itself — and nothing enforces it. It holds because line 693 is a
  *copy*; it breaks the moment anything assigns a derived value there
  (`?.copy(...)` to add context), and `detail()` then silently drops the "the
  drain aborted" framing for every failed drain.

**How to apply:** when the widening lands, the invariants to hold are that
nothing branches on the condition (`NEEDS_ATTENTION` must stay with zero readers
in `core/src/main`), that `drainMessage` receives the *drain* arm only (a widened
flag would make it assert a blocked drain is failing — item 27), and that
`attentionMessage` never says "the drain cannot finish" about a server with no
drain and never asserts joinability. `DisplayConformanceTest` needs a second
property — *a server carrying a PERMANENT failure is flagged, whatever its badge*
— driven through `forbiddenTransition`, which is the case that renders `RUNNING`.

## Round 12 rulings: the Velocity control surface (`:velocity-plugin`)

Audited on `feat/velocity-proxy-kind` with `:core` not yet written. The module
contains no container operation at all, so none of the seven stop invariants can
be violated from inside it; every finding is about what it makes `:core` able or
unable to do correctly.

- **The seal/deregistration separation holds.** Only `DELETE /v1/backends/{name}`
  reaches `unregisterServer`. There is no set-assert endpoint, so no backend can
  be removed by omission; `ADDRESS_CONFLICT` refuses the upsert that would have
  hidden an unregister inside step 2; a finished sweep and a seal touch only
  `transfers` and `AdmissionRegistry`. Do not re-derive this — re-check it only if
  a list-shaped assert or a force flag is proposed.
- **`PlayerHandle` having no `disconnect` is real, not decorative.** The sweep is
  written against the port, the adapter uses `connect()` (never
  `connectWithIndication`/`fireAndForget`), `classify` is exhaustive with no
  `else`, and `TransferNeverKicksTest` greps the module's sources. Every failure
  branch counts and leaves the player attached.
- **`InitialChoice.AdmitAnyway` is the right trade** (Velocity issue 689: a
  login-path `denied()` strands the client). The leak is bounded by `:core`'s own
  SLP zero-player gate, so it costs convergence, never data. What is *not* right
  is the claim that the leak is always counted — see
  [[drain-audit-danger-patterns]] item 45.
- **Refusing `DELETE` on a populated backend with no force flag is correct** and
  does not by itself wedge `:core`: the occupancy resolves when players leave or
  when the backend dies. The only path that makes it permanent is the wedged
  sweep (item 43), and the fix belongs there.
- **The proxy's own seal (`PUT /v1/proxy`) is load-bearing for convergence and
  nothing says so.** With every backend sealed, `onInitialChoice` returns
  `AdmitAnyway` for every joining player, so a fleet-wide drain never reaches
  zero. `onPreLogin` is the only mechanism that stops it, and it is safe (a
  refused login is not a disconnect). `:core` must assert it before sealing the
  last admitting backend.
- **`/v1/state`'s `backends[].players` is the tempting wrong gate.** It is the
  proxy's view and cannot see a client connected straight to the backend port
  ([[drain-audit-danger-patterns]] item 36). It is the right source for the
  `BACKEND_OCCUPIED` guard — a directly-connected player is unaffected by
  deregistration — and the wrong source for the stop gate. Both facts need to be
  in the protocol doc before `:core` reads the field.

**How to apply:** when `:core`'s proxy client lands, the first three questions are
(1) does it keep the transfer retry count itself, since a fresh sweep zeroes the
tallies, (2) does it treat a counter going *down* as a proxy restart that voids
evacuation evidence, and (3) does it ever send a `PUT` address derived from the
desired definition rather than the running container.

## Round 14 rulings: the transfer bound is a clock

- **A purely temporal bound satisfies the plugin author's handover, and the count
  loses nothing.** The handover ("`:core` owns the step-4 retry limit, because a
  fresh sweep zeroes the plugin's tallies") is a statement about *where* the bound
  lives, not about its unit: it forbids `:core` inferring "stop asking" from the
  proxy's own counters. A duration held by `:core` is orchestrator-side, survives a
  fresh sweep and survives a plugin restart, so it implements the handover rather
  than reinterpreting it. A count-based ceiling protects nothing here — an ask
  costs the counterparty nothing (start-or-join dedupes) and a settled sweep can
  restart every pass — so its removal is a deletion, not a gap. Do not re-propose
  a count. What the trade *does* cost is that the bound now rests on one nullable
  timestamp: see [[drain-audit-danger-patterns]] items 58 and 59.
- **`wanted` dropping undecodable rows is not a choice between two unrepairable
  states.** The framing ("repair it and the sweep suppresses GC for a whole pass")
  is a false dilemma; the store already publishes the third option.
  `ServerListing.unreadable` carries the stored name for exactly this consumer, so
  `wanted` should be the matched names **union** the unreadable names: per-pass GC
  keeps working for every readable row and only the specific unreadable names are
  exempted. Not a data-loss path — the plugin refuses `DELETE` on an occupied
  backend with no force flag — so it is a warning, but it became live rather than
  theoretical with `DefinitionCodec.rebuilding`, which deliberately widens what
  counts as a corrupt definition row.

## Round 15 rulings: the anchor holds, the resume does not

- **`DrainStatus.transferStartedAt` is the right anchor and the enumeration
  checks out.** Every path that can reach `exhausted` passes a stamp: both
  `secureDestination` branches that enter `TARGET_RESOLVED` with a router, and
  `transferStep`'s players-online branch for the three paths that never had a
  bodied step-3 pass. The no-router entry to `TARGET_RESOLVED` does not stamp and
  does not need to — `transferStep` short-circuits to `requireEmpty` whenever
  `router == null || destination == null`. Nothing clears it: `moveTo`,
  `forgetSaveEvidence`, `forgetSaveConfirmation`, `dropUnusableSaveEvidence`,
  `abort`, `blocked`, `restoreRegistration` and the container-down branch are all
  `copy`-based, and the only `drain = null` sites in `Reconciler` discard the
  whole record with the workload. Persistence does not reopen critical 2: the key
  is nullable (`PropertyDocument.instant` returns null on a missing key),
  `ENCODING_VERSION` is unchanged at 1, and an old row costs one extra allowance.
  Do not re-litigate the anchor.
- **The ruling on module ownership: a two-line codec edit by the `:core` owner is
  what the rule intends.** CLAUDE.md's "changes spanning `:schema` or `:cri` go to
  a single agent as one unit" is a rule against *leaving one side stale*, not a
  rule about who may type in which directory. The `:store` and `:api` edits here
  are the mechanical consequence of one `:schema` field — one `scope.put`, one
  `reader.instant`, one `put` in `ServerJson` — and routing them would have split
  a single semantic change across three reviews with a window in which the field
  existed and was not persisted. The line to hold is behavioural: route when the
  other module has to make a *decision* (a migration, a precedence, a badge rank),
  keep it when the other module only has to carry the value.
- **A green suite with no test XML is not a result.** A wedged Gradle daemon that
  reports `BUILD FAILED` and writes no reports is indistinguishable from "nothing
  ran"; treating it as an unknown was correct. Never accept a signed count that
  cannot be traced to per-module XML.
