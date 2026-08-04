---
name: api-contract-subtleties
description: Three non-obvious traps in the :api contract — the split null policy, ordering cursor before list, and CORS on the SSE response
metadata:
  type: project
---

Things about the `:api` contract that are easy to get wrong on a later change and
that reading the code alone does not make obvious.

**The null policy is deliberately split, and reversing it breaks round-tripping.**
The `definition` sub-document **omits** absent optional fields; every other object
renders them as explicit `null`.

**Why:** `:schema` treats an explicit `null` as a violation rather than as
"unset" (see `examples/invalid/explicit-null.yaml`). A definition rendered with
explicit nulls comes back as a 422 the first time a dashboard round-trips it.
Nothing else in a response is ever sent back, so nothing else pays that price.

**How to apply:** if somebody asks to "make the JSON consistent", that is the
change that breaks GET→edit→PUT. The affected fields are `paper.build`,
`network.hostPort`, `network.rcon`, `resources.cpu`, `storage.volume.size`,
`placement.node`, and `metadata.labels` when empty.

**Read the cursor before the list, never after.** `GET /servers` and the stream's
snapshot both call `currentCursor()` first, then `listServers()`.

**Why:** the other order loses a definition written between the two reads — it is
in neither, so a dashboard that lists then streams never learns about it. This
way it is merely duplicated, which a client keyed by name absorbs silently.

**CORS headers go onto the exchange before the handler runs, not onto the
returned `Response`.**

**Why:** the SSE handler never produces a `Response` — it takes the exchange over
and calls `sendResponseHeaders` itself. Folding CORS in at the end of dispatch
leaves exactly one endpoint broken cross-origin, and it is the one a dashboard
holds open all day. This was a real bug, found while writing §8 of `API.md`
rather than by a test, and it is now pinned by an assertion on the stream's own
headers.

**How to apply:** any new header the dispatcher adds globally has the same
hazard. Ask whether the stream gets it.

**The SSE keep-alive is a `ping` event, never a comment frame.** `EventSource`
does not expose comment frames to script.

**Why:** on an idle fleet the keep-alive is the only traffic between the opening
snapshot and the lifetime cycle 30 minutes later, so with a comment a half-open
socket left an `EventSource` client rendering stale state with
`readyState === OPEN`. Found by the dashboard team, who worked around it by
using `fetch` + `ReadableStream` instead.

**How to apply:** keep every frame a named event so both transports see the same
protocol. If something wants to be "out of band", it is not.

**Anything the dashboard must render or offer belongs in `/api/v1/meta`.** Two
spellings: observed-state enums by Kotlin name (`RUNNING`), definition enums by
YAML wire value (`persistent`). A create form offering `PERSISTENT` builds a
document the parser rejects.

**Why:** `storageMode` and `drainPolicy` were missing and the dashboard
hard-coded them, which is precisely what the endpoint exists to prevent.

**Absence means purged, and only purged.** Anything the store cannot read is
reported as unreadable — in its own array on the list and the snapshot, as an
`unreadable` event on the stream, as `SERVER_UNREADABLE` on a single fetch. It is
never omitted and never `removed`.

**Why:** a ninth drain audit found one undecodable row aborting `listServers`,
which blanked the fleet table and stopped the reconcile loop queueing work at the
same instant. `:store` now has tolerant reads (`listAll`, `StoredServer.unreadable`,
`neverObserved`) and `:api` uses them.

**How to apply:** the two traps are silent. Testing `status === null` conflates
"not observed yet" with "observation is corrupt" — use `neverObserved`. And any
absence check on the stream must count unreadable rows as present, or a bad row
reports a deletion that never happened on a server with players on it.

**A badge for our own record is not a badge for the world.** `UNREADABLE` (our
copy will not decode) is distinct from `UNKNOWN` (the node could not be reached).
Sending an operator to the wrong one of those wastes an outage.

**A state and a flag answer different questions, so set both.** `display.state`
is a single badge and something always outranks something else — `TERMINATING`
outranks `UNREADABLE` — so any fact the badge can lose needs a flag beside it.
`unreadable` says *what* is wrong and is what a dashboard filters on;
`needsAttention` says *somebody must act* and is what an alert fires on.

**Why:** the tenth drain audit caught `needsAttention` not being set for an
unreadable row. Its charter is "the loop has stopped and only a person can move
this", which a row that decodes identically on every pass satisfies exactly.
Leaving it off meant the one audience that must see these servers — whoever
alerts — never would.

**How to apply:** when adding a `display` value, ask separately what filters on
it and what alerts on it. If those differ, it is a state *and* a flag.

**Absence can only be derived when every row can be named.** `UnreadableServer.name`
is nullable, and a nameless row may *be* any previously-seen server whose name
column was nulled. While one exists, the stream suspends `removed` for every row.

**Why:** the alternative is reporting a deletion that never happened on a server
that may have players on it. A stale dashboard beats an operator believing a
running server is stopped.

**How to apply:** any derivation keyed on identity has this failure mode when
identity is optional. Ask what happens when the key is null before writing the
loop — and tell the client the derivation is suspended, because a dashboard that
silently stopped reporting deletions is worse than one that never reported them.

**One fact, one derivation — and a reassurance never outranks a failure.**
`display.drainBlocked` and `display.detail` must both come from the
`DRAIN_BLOCKED` *condition*, never from `drain.blocked` directly. In `detail`,
`status.failure` (when it differs from `drain.failure`) beats `drain.failure`
beats the block.

**Why:** the eleventh drain audit found the two derived separately and
disagreeing. The reachable sequence needs no bad data: a drain blocks on players,
the next pass throws a `NodeException`, and `Reconciler.nodeFailure` carries the
block forward while recording on `status.failure` — so an operator was told
"waiting, not stuck" about a server whose node was unreachable.

**How to apply:** when a `require` enforcing disjointness is declined for cost
reasons, the precedence becomes the entire specification and must hold at *every*
site that reads it — extract a function rather than trusting review. And check
whether two failure fields are the same event before ranking them: an aborted
drain writes the same value to both, so a naive "newest wins" drops the better
wording.

**Never tell a client to derive a status fact in TypeScript.** If `API.md` needs
`server.status?.failure` to decide what to *render*, the fact belongs in a
condition and should reach the client as a flag.

**Why:** I added `: server.status?.failure ? 'not progressing'` to the drain chip
snippet, which made dashboards derive "the loop has stopped moving this server" a
fourth time with no threshold — so a one-pass blip rendered as a problem. The
eleventh-round audit cited that snippet as the strongest argument the fact
belonged in `NEEDS_ATTENTION`, which then widened to cover it.

**How to apply:** thresholds and precedence live server-side. A client snippet in
`API.md` should read `display.*` flags almost exclusively; reaching into `status`
to compute a judgement is the smell.

**Invariants stated in bold in `API.md` age badly.** "`drainBlocked` and
`needsAttention` are never both true" became false when `NEEDS_ATTENTION` widened
beyond drains, and the doc had built render guidance on it.

**How to apply:** prefer "order these, first wins" to "these are exclusive". An
ordering stays correct when a flag's scope widens; an exclusivity claim does not,
and any snippet built on it silently renders the wrong thing.

Related: [[api-module-decisions]], [[divergences-from-drain-audits]].
