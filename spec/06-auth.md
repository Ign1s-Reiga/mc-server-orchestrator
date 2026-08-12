# 06 — Multi-identity authentication

**Specified separately, in [`auth/`](auth/README.md).**

It is not a console feature. The console is its first consumer and the reason it
is being written now, but what it requires is a change to the whole API's
authorization model — every existing endpoint acquires a tier decision, and that
is the bulk of the work.

## Why the console needs it

`OperatorAuth` holds a **single** token digest. Sessions are exchanged for that
one token, so every credential in the system carries identical authority.
`api/API.md` says it outright:

> **There are no roles** — any authenticated caller can do anything the API
> offers.

So there is nothing for [Gate 2's tiers](03-command-policy.md) to attach to, and
nothing for [the audit record's](04-output.md) `identity` field to name.

## The ordering that matters

**The console's tier gate ships after the API-wide tier assignment, not before.**
A tier honoured by exactly one endpoint is worse than no tier: an operator who
sees `viewer` refuse a console command reasonably concludes `viewer` is
constrained, and that conclusion would be false everywhere else.

See [`auth/03-authorization.md`](auth/03-authorization.md) §5.

## What the console specifically requires from it

| | |
|---|---|
| An identity on every request | [04-output.md](04-output.md) §3 — the audit record names who ran the command |
| A total ordering on tiers | `min(identity tier, server ceiling)` in [03-command-policy.md](03-command-policy.md) §4 |
| `tier` on `GET /auth/session` | So the dashboard renders the console it may use rather than discovering limits from `403`s |
| `FORBIDDEN` distinct from `UNAUTHENTICATED` | A dashboard that retries the login on an insufficient tier loops |

Nothing else here depends on the shape of identity management.
