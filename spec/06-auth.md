# 06 — Multi-identity authentication

**This is the prerequisite and the long pole.** Nothing in Gate 2 of
[03-command-policy.md](03-command-policy.md) can be built before it.

## Why it is required

`OperatorAuth` holds a **single** token digest. Sessions are exchanged for that
one token, so every credential in the system carries identical authority.
`api/API.md` states it plainly:

> **There are no roles** — any authenticated caller can do anything the API
> offers.

Its threat model is explicit that this is a design position, not an oversight:
*"a single-host operational tool with one class of user — the operator — and no
tenancy"*, and the absence of a lockout is justified by the credential being
shared.

"Permissions determined by the login token" therefore presupposes something that
does not exist. There is nothing for a tier to attach to.

`api/API.md` §11 lists per-user roles among things deliberately absent, *"not
needed at this scale"*. The premise has changed — see
[01-impact.md](01-impact.md) §4 — and the reversal should be recorded as a
change of premise, not a change of mind.

## Model

### Identity

Stored in `:store`, behind the interface, with no storage-engine specifics
leaking:

| Field | Notes |
|---|---|
| `name` | operator-facing, unique |
| `credentialDigest` | SHA-256, compared in constant time — same discipline as the current token |
| `tier` | `viewer` / `operator` / `admin` |
| `enabled` | disabling must not require deleting, so an audit trail keeps referents |

The existing `MCORCH_API_TOKEN` **stays**, mapped to a built-in `admin` identity.
Two reasons: scripts, `curl` and CI depend on the bearer path today, and a
bootstrap credential is needed to create the first real identity. Removing it
would be a breaking change to an interface `api/API.md` §12 documents.

### Credential

`Credential.OperatorToken` becomes an identity-bearing type. Every call site that
today knows only *authenticated* must come to know *who*, because the tier gate
and the audit sink both need it.

The two credential paths are unchanged in mechanism:

- `Authorization: Bearer <token>` — no CSRF, because a browser never attaches the
  header on its own;
- `Cookie: mcorch_session=…` — CSRF required on mutating requests.

A console command is a `POST`, so a cookie-authenticated console request carries
`X-CSRF-Token` like any other mutation.

### Session

`SessionRegistry` binds a session to an **identity**, not merely to "the
authenticated caller". Session TTL, `HttpOnly`, `SameSite` and the double-submit
CSRF token are unchanged.

`GET /api/v1/auth/session` gains `identity` and `tier`, so the dashboard can
render the console affordance it is actually allowed to use rather than
discovering the tier from a `403`.

## What this does not change

- **The threat model's other four entries stand.** Loopback default bind,
  `HttpOnly` cookie, CORS allow-list, constant-time compare, fixed failure delay,
  no lockout.
- **Still no tenancy.** Identities differ in *tier*, not in which servers they can
  see. Per-server visibility is a much larger feature and is not proposed here;
  the per-server ceiling in [03-command-policy.md](03-command-policy.md) §4
  constrains what a tier may *do* on a server, not what it may see.
- **A hostile operator remains out of scope**, as today. Tiers reduce blast radius
  and make actions attributable; they do not defend against an `admin` acting in
  bad faith.

## Scope warning

This is a `:store` schema migration, an `:api` auth rework, and a new set of
endpoints for managing identities — with its own authorisation questions (who may
create an identity, who may raise a tier, whether an `admin` may raise their own).

It is worth deciding early whether the console justifies it on its own, or whether
it should be specified and built as its own piece of work with the console as its
first consumer. **The second is more honest about the size**, and it lets the
invariant gate — which needs none of this — ship first.
