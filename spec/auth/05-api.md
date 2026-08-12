# 05 — The endpoints

## 1. `/api/v1/auth/session` reports who you are

`GET` gains `identity` and `tier`:

```json
{ "authenticated": true, "method": "session",
  "identity": "rin", "tier": "operator",
  "csrfToken": "9Xk…", "expiresAt": "2026-08-12T22:15:30Z" }
```

The dashboard calls this on page load already. With `tier` in the response it can
render the affordances it is allowed to use, rather than discovering its limits
from a scatter of `403`s — which is the difference between a UI that looks
considered and one that looks broken.

`POST` is unchanged in shape. It now accepts **any** identity's credential rather
than only the operator token, and binds the session to whoever presented one.

A bearer caller gets `identity` and `tier` too; `csrfToken` and `expiresAt` stay
`null` as today.

## 2. Identity management

All `admin`. All audited.

| | |
|---|---|
| `GET /api/v1/identities` | Name, tier, enabled, `createdAt`. **Never a digest** |
| `POST /api/v1/identities` | Creates. Response carries the generated credential — the only time it exists outside the caller |
| `PUT /api/v1/identities/{name}` | Tier and `enabled`. Not the credential |
| `POST /api/v1/identities/{name}/credential` | Rotates. Same once-only response |
| `DELETE /api/v1/identities/{name}` | Removes. Prefer `enabled: false` — see [02-model.md](02-model.md) §1 |

### The credential appears exactly once

`POST /identities` and the rotate route are the only responses in this API that
carry a secret, and they carry it because there is no other channel — the system
has no mail, no side band, nothing.

They must therefore be the loudest thing in the contract: **the value is not
stored in recoverable form and cannot be shown again.** A caller that loses it
rotates; it does not recover.

This is a deliberate exception to `api/API.md` §13's *"No secret material,
ever"*, in the same way the console is one to the PII half — and, like that one,
it is written into §13 as part of the change rather than discovered later. The
difference worth noting in the amendment: §13's existing sentence is about
material the *operator* supplied, which the API genuinely never returns. This is
material the API *generated*, and returning it once is the only way it can ever
be used.

### Rotation does not log anyone out, and that is wrong by default

A rotated credential leaves live sessions alive, because a session was resolved
to an identity when it was created ([02-model.md](02-model.md) §4).

An operator rotating a credential is usually responding to it having leaked, so
the sessions are the thing they most want gone. **Rotation sweeps that identity's
sessions**, and so does disabling. If a caller genuinely wants a rotation that
does not interrupt anyone, that is a flag on the request, not the default.

## 3. Errors

New codes, in the shape `api/API.md` §3 already uses.

| code | status | carries | meaning |
|---|---|---|---|
| `FORBIDDEN` | 403 | `requiredTier` | Authenticated, insufficient tier. Distinct from `UNAUTHENTICATED`: the caller does not need to log in again, and a dashboard that retries the login on this loops |
| `IDENTITY_EXISTS` | 409 | | `POST` never overwrites, matching how `POST /servers` behaves |
| `IDENTITY_NOT_FOUND` | 404 | | |
| `LAST_ADMIN` | 409 | | The change would leave no enabled `admin` — see §4 |

`FORBIDDEN` carries `requiredTier` so a dashboard can say *"this needs admin"*
rather than *"forbidden"*. It does **not** carry the caller's own tier, which the
caller already knows from `/auth/session`.

## 4. The last admin

Deleting, disabling or demoting the only enabled `admin` is refused with
`LAST_ADMIN`.

Not because it is unrecoverable — the operator token gets you back in, which is
half of why it exists ([06-bootstrap.md](06-bootstrap.md)) — but because the
recovery requires reaching the host's environment, and an operator who does not
realise that is one click from an orchestrator they can only fix by shell.

The refusal names what to do first: create or enable another `admin`.

> This check is racy in the honest sense — two concurrent demotions could each
> see the other as the survivor. The store write is the serialisation point, so
> the check belongs there rather than in the route, and a test should drive it
> concurrently rather than assume the window is too small to hit.
