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

**Why:** these are human decisions, not accidents — do not report the missing
seal/transfer/deregister bodies as skipped steps, and do not propose a timeout
that stops anyway.

**How to apply:** audit against these postures. The interesting question is never
"did it skip a proxy step" but "does the absence of a seal let the player count
go stale, and does the permanent-abort posture create pressure toward a manual
force stop". See [[drain-audit-danger-patterns]], especially items 7 and 8.
