# 04 — Command output

## Decision

**Raw server output crosses the API boundary unmodified.** The console is
general-purpose: any command the gates permit may be run, and whatever the server
replies is returned to the dashboard verbatim.

This is a deliberate exception to `api/API.md` §13, which today reads:

> **No player names, UUIDs or client addresses.** Not by filtering — there is
> nothing in the objects to leave out.

After this, that sentence is true of every endpoint **except the console**. The
exception is written into §13 as part of the same change that ships the endpoint,
and `ResponseLeakageTest` gains an explicit carve-out for it rather than
discovering the collision during implementation.

### Why

A general console is a product requirement, and it cannot be built any other way.
The alternative — typed parsers producing shapes with no field an identity could
occupy — preserves the guarantee but is not a console: every command needs a
parser written before it works, and a Forge mod's custom command cannot be
supported at all. On a modded fleet that is a permanent maintenance obligation
and a permanent ceiling on what the feature can do.

The exposure is real and is accepted as an operator-responsibility matter: a
credential that reaches this endpoint sees whatever the server prints, including
player names, UUIDs and client addresses. That is a property of handing someone a
console, and the tiers of [03-command-policy.md](03-command-policy.md) are how it
is bounded.

### Say it accurately

The endpoint **returns unredacted server output to authorised operators.** It does
not "mask sensitive data", and it must never be described that way in the
contract, in the UI, or to an operator deciding who gets a credential — because
the decision they are making is who may read player identities.

---

## 1. Why not mask it

Recorded so it is not re-proposed as a middle path. Substring masking of
unstructured text **fails open**: output formats vary by server version and by
plugin, a Forge mod returns text in a shape nobody has seen, and unknown format
means unmasked. The failure mode leaks silently, on exactly the deployments
hardest to test.

The polarity is wrong too. `/banlist ips` sits at op level 3, so a scheme that
masks for lower tiers and reveals for higher ones shows asterisks to the level-3
user and raw client IP addresses to the level-4 one.

A console that half-redacts is worse than one that does not, because it invites
the belief that it did.

## 2. Identities now flow in both directions

Under the typed-parser design the endpoint was asymmetric — names went in as
command arguments and never came back — which meant a dashboard could not show
who was online and so could not offer a player to click.

That constraint is gone. `list` returns names, so the dashboard can render a
player list and act on it, and click-to-kick works.

The consequence to carry forward is that **the request and the response are now
equally sensitive**, and neither may reach a place that keeps it. See
[08-origin-and-client.md](08-origin-and-client.md): the command travels in the
body rather than the query so it stays out of access logs, and same-origin
removes the intermediaries that would otherwise hold copies of both halves.

## 3. What does *not* change

**The audit sink stays redacted.** That is CLAUDE.md's rule — *never log player
names, UUIDs, or IP addresses* — and it governs what the orchestrator writes to
disk, not what it returns to an authenticated caller. This decision is about the
response body; it does not touch the log.

So the audit record keeps its shape:

| Field | Notes |
|---|---|
| identity | who ran it — see [06-auth.md](06-auth.md) |
| server | the declared object's name |
| command | the command as dispatched |
| tier | the effective tier the request ran at |
| at | timestamp |
| outcome | executed, refused-by-gate-1, refused-by-tier, unavailable, timed out |
| client | the `X-Mcorch-Client` value, **as claimed** — forgeable, never verified, never authorised on |

**Not the output.** The reply is where the identities are densest, and nothing
about auditing requires keeping it: the question an audit log answers is who did
what and when.

`command` records what was dispatched rather than a matched allow-list entry,
because under a general console there is no entry to match for the top tier. An
argument may therefore carry a player name — `kick Alice`.

**How much of it is kept is declared per server**, by
`spec.console.auditCommandText` — see [03-command-policy.md](03-command-policy.md)
§4.1. It defaults to recording the verb and an argument count, which is the
setting that agrees with the logging rule above; `true` keeps the command
verbatim. The choice sits in the manifest rather than in global configuration
because the servers where full command text is worth its retention cost are not
usually the whole fleet.

The setting governs the audit sink alone. The console returns raw output either
way, and nothing about it decides whether a command runs.

**Gate 1 is untouched.** `stop` and `save-off` are refused for every identity and
every tier, general console or not. See
[03-command-policy.md](03-command-policy.md) §1 — those refusals are about data
loss, not about disclosure, and nothing in this decision reaches them.
