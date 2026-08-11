# The status a client renders

`api/API.md` says what the status fields *are*. This says what they **mean over
time** — which values follow which, which resolve themselves, and which are a
server sitting still waiting for a human. It is written for whoever builds a
dashboard, because most of the ways a fleet view lies to an operator are ways of
collapsing two of the distinctions below into one badge.

The enumerations here are `:schema`'s, and `GET /api/v1/meta` serves the live set.
Where they disagree, `/meta` wins — a client may meet a value it was not taught.

---

## Three fields, three questions

| Field | Question it answers |
|---|---|
| `status.phase` | Where in its life is this server? |
| `status.conditions[]` | What is true about it right now? |
| `status.drain` | If it is being stopped, how far has that got? |

They are not three views of one value and must not be rendered as one. A server
can be `RUNNING`, have a `DRAINING` condition that is false, and still carry
`NEEDS_ATTENTION` — which is precisely the state a stalled drain leaves behind,
and the one an operator most needs to see.

`status.observedAt` is when the loop last looked. **A status that stops advancing
is an alert, not a steady state**, so a client should show the age of the
observation somewhere and treat a stale one as suspect rather than as current.

---

## `phase`

| Phase | Means | Resolves itself |
|---|---|---|
| `PENDING` | Accepted, not acted on yet | yes |
| `IMAGE_PULLING` | Pulling; the first pull of a server image is minutes | yes |
| `CREATING` | Sandbox and container being made | yes |
| `STARTING` | Container up, **not joinable yet** | yes |
| `RUNNING` | Running — see `ready` for whether players can join | — |
| `DRAINING` | Being stopped; `status.drain` says how far | usually |
| `STOPPING` | Stop dispatched | yes |
| `STOPPED` | Container gone | — |
| `FAILED` | Permanently failed; see `failure` | **no — needs a human** |
| `UNKNOWN` | The node or runtime could not be reached this pass | yes, and **not a reason to act** |

`UNKNOWN` deserves care. It is the loop admitting it could not look, not a report
that something is wrong, and a dashboard that paints it like a failure teaches
operators to ignore the colour that means failure.

`RUNNING` is not "joinable". That is the `READY` condition.

---

## `conditions[]`

Every condition is `TRUE`, `FALSE` or `UNKNOWN`, with a `message` and a
`lastTransitionAt` — *since when*, which is the number to put beside it.

| Condition | Kinds | Notes |
|---|---|---|
| `IMAGE_AVAILABLE` | both | |
| `VOLUME_BOUND` | both | `TRUE` with a message on an ephemeral workload: no volume is bound, and that is correct |
| `CONTAINER_RUNNING` | both | |
| `READY` | both | Actually joinable, not merely running |
| `DRAINING` | both | |
| `DRAIN_BLOCKED` | both | **Waiting, not stuck.** See below |
| `PLAYERS_EVACUATED` | both | |
| `WORLD_SAVED` | both | `FALSE` on a fresh server means only that no save has been confirmed yet |
| `BACKENDS_RESOLVED` | proxy | `FALSE` is not a failure — it is the answer to "why can nobody join" |
| `CONTROL_ENDPOINT_READY` | proxy | `FALSE` means no backend behind this proxy can complete a drain |
| `NEEDS_ATTENTION` | both | **This will not fix itself** |

### `DRAIN_BLOCKED` and `NEEDS_ATTENTION` are opposites, and both can be true

`DRAIN_BLOCKED` says *do not act* — the drain has stopped advancing and nothing
is wrong; today it means the server is waiting for players to log off, and it
resumes on its own. `NEEDS_ATTENTION` says *act*.

They were once documented as never both true. **They can be**, because
`NEEDS_ATTENTION` is raised from the failure recorded on the pass as well as the
drain's own: a drain can be quietly waiting for players while the node it runs on
has become unreachable. A client that renders the pair as a single tri-state will
hide one of them. Show the attention flag separately, always.

Also: `DRAIN_BLOCKED` does **not** mean the workload is joinable. A workload that
seals its own login path shuts that path before the gate it is blocked on, so a
blocked drain is often a blackout that resolves precisely because nobody new can
get in.

---

## `drain`

`status.drain.state` walks the drain protocol. The steps are the ones in the
README, and each has a state:

| Step | State | What has happened |
|---|---|---|
| 1 | `DRAIN_REQUESTED` | Marked; the server stops being a placement target |
| 2 | `SEALED` | No new logins; existing players untouched |
| 3 | `TARGET_RESOLVED` | Somewhere with capacity exists |
| 4 | `TRANSFERRING` | Players being moved, with retries |
| 5 | `SAVING` | Save requested — **and the completion not yet confirmed** |
| 6 | `DEREGISTERED` | Backend removed from routing, after zero players and a confirmed save |
| 7 | `STOPPING` | Stop dispatched; only now |
| — | `DRAIN_FAILED` | Aborted |

**`DRAIN_FAILED` does not mean stopped.** There is no edge from it to a stop: the
container keeps running and stays joinable. A dashboard that renders it with the
vocabulary of shutdown — greyed out, "stopped", a tombstone icon — describes the
opposite of the truth. The right rendering is an alarm on a server that is still
serving players.

`status.drain.blocked` is separate from `status.drain.failure`. Blocked is the
protocol working; failure is not. When a record carries both, it is reported as
failed — the louder of the two — because somebody logging in does not resolve a
world save that was never confirmed.

---

## `failure.reason`

Every failure carries a `failureClass` of `RETRYABLE` or `PERMANENT`. `PERMANENT`
means the loop has stopped trying; it never means the container was stopped.

**Bring-up:** `IMAGE_PULL_FAILED`, `IMAGE_REFERENCE_REJECTED`,
`SANDBOX_CREATE_FAILED`, `CONTAINER_CREATE_FAILED`, `CONTAINER_START_FAILED`,
`CONTAINER_EXITED`, `READINESS_TIMEOUT`, `VOLUME_UNAVAILABLE`.

**Environment:** `NODE_UNAVAILABLE`, `RUNTIME_UNREACHABLE` — usually the runtime
is simply not there. Expect these on a development orchestrator with no
containerd; they are retryable and the fleet recovers when it returns.

**Drain:** `DRAIN_NO_DESTINATION`, `DRAIN_TRANSFER_FAILED`, `DRAIN_SAVE_TIMEOUT`,
`DRAIN_STALLED`.

**Proxy:** `PROXY_CONTROL_UNREACHABLE`, `PROXY_PLUGIN_INCOMPATIBLE`,
`FORWARDING_SECRET_UNAVAILABLE`.

Plus `UNKNOWN`. The set is closed but not fixed — values are appended as kinds
arrive, so switch with a default arm rather than exhaustively.

### `DRAIN_NO_DESTINATION` is not "waiting for players"

The two look alike and mean opposite things. *"No transfer counterparty exists, so
wait for people to log off"* is the protocol working, and is recorded as a
**block**, not a failure. *"The search ran and the fleet is full"* is
`DRAIN_NO_DESTINATION` — the fleet is too small and a human has to add capacity.
They used to be one value; a client that merges them re-creates the bug.

---

## Distinctions worth keeping in the UI

Four pairs, each of which is one honest field and one guess if collapsed:

1. **`status: null` with `neverObserved`** — not looked at yet — versus
   `status: null` without it, which is a stored observation the store could not
   read. Only `neverObserved` tells them apart.
2. **`backends: null`** — nothing has looked yet, resolves itself — versus
   `backends` present with `matched: 0` — the selector matches nothing and the
   proxy is routing players nowhere. The second needs a human.
3. **`DRAIN_BLOCKED`** (wait) versus **`NEEDS_ATTENTION`** (act), above.
4. **A drain that is progressing** versus one whose `observedAt` has stopped
   moving. The states look identical in a table that shows only the state name.

---

## What a stalled drain looks like

Worth recognising, because it is the one state where the server is simultaneously
healthy and broken:

```
phase              RUNNING
CONTAINER_RUNNING  TRUE
DRAINING           FALSE   drain state DRAIN_FAILED, failing since …
NEEDS_ATTENTION    TRUE    this server needs a human: …
PLAYERS_EVACUATED  TRUE
WORLD_SAVED        FALSE   no save has ever been confirmed for this server
failure            DRAIN_STALLED / PERMANENT
```

Players can still join it. Nothing further will be attempted. The message on
`NEEDS_ATTENTION` names the remedy, and `docs/operating.md` note 1 covers the
common cause — a persistent server whose world save cannot be confirmed — along
with the case where the advice in that message does not currently hold.

---

## See also

- `api/API.md` §6 for the resource shape, §7 for the derived `display` badge, §8
  for the event stream.
- `docs/operating.md` for behaviours that are deliberate, correct and surprising.
- `docs/troubleshooting.md` for a symptom index.
