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

Related: [[api-module-decisions]].
