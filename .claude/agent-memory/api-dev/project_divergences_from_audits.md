---
name: divergences-from-drain-audits
description: Where :api deliberately differs from a drain-audit ruling, and the reasoning that was accepted — check here before "fixing" an apparent inconsistency
metadata:
  type: project
---

`:api` deliberately differs from the tenth drain audit's ruling in two places.
Both were reviewed by the coordinator and left as built; both are argued inline
in `api/API.md` so a reader sees a decision rather than an inconsistency.

**`display.state` uses a new `UNREADABLE` value; the ruling said reuse `UNKNOWN`.**

**Why:** `UNKNOWN` means the node or runtime could not be reached — go and look
at the host. `UNREADABLE` means our own stored record will not decode — go and
repair a row, while the container very probably runs on untouched. An operator
sent to the wrong one wastes an outage. The ruling's cost (an enum value plus a
frontend release) is half real: `meta.enums.displayState` exists so a new badge
needs no frontend change, and a test pins both halves — the value is advertised
and `?state=UNREADABLE` selects on it.

**How to apply:** if a future ruling asks to collapse a state on
"avoid a `/meta` change" grounds, that half of the argument is answerable by
pointing at the `/meta` mechanism. The other half — is this genuinely a different
problem with a different remedy — is the one to argue on merits.

**`GET /servers/{name}` answers 200 with the mark; the ruling said keep raising.**

**Why:** the ruling's principle ("a named read wants the failure, not a snapshot
with a hole in it") is right, and is why `:store`'s `getServer` is strict — but
it targets a caller with nowhere to put the fact, which would then silently
report "no observation". This API has `unreadable` and the badge. Raising too
would mean the list shows a row that 500s when clicked, teaching operators the
dashboard is broken rather than that a row is.

**How to apply:** the principle is preserved where it bites, and that is what
made the divergence acceptable — `/status` still raises (it exists to serve an
observation and cannot serve one it cannot read) and the failure is
`SERVER_UNREADABLE`, never `NOT_FOUND`. When diverging from a ruling, keep the
part that bites and say which part you kept.

Also noted by the coordinator as something the ruling should have anticipated:
`PUT` with `If-Match: *` treats an unreadable definition as existing, which makes
the broken case repairable. The documented unwedging action is an operator
editing the definition — if that path required the old definition to be readable,
the one case needing repair would be the one case that could not be repaired.

Related: [[api-contract-subtleties]].
