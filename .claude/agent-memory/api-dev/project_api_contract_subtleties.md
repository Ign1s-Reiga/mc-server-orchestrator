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

Related: [[api-module-decisions]].
