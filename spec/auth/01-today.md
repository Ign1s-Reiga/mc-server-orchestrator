# 01 — What exists today

## 1. One credential, worn two ways

`OperatorAuth` holds a **single** `expected` token digest. Everything else is a
way of presenting that one secret:

- `Authorization: Bearer <token>` compares against it directly and yields
  `Credential.OperatorToken`.
- `POST /api/v1/auth/session` checks the same token and issues a session;
  `Credential.Session` follows from a cookie.

`SessionRegistry.Session` carries `idDigest`, `csrfToken`, `createdAt` and
`expiresAt`. **There is no field naming who the session belongs to**, because
there has only ever been one answer.

`api/API.md` states the consequence without hedging:

> **There are no roles** — any authenticated caller can do anything the API
> offers.

And its threat model states the premise:

> This is a single-host operational tool with one class of user — the operator —
> and no tenancy.

`api/API.md` §11 lists per-user roles among things deliberately absent, *"not
needed at this scale"*. **That is the sentence this specification reverses**, and
the justification is a change of premise rather than a change of mind: planned
Forge support implies larger fleets and more than one person operating them, and
a console is a facility whose safe use depends on knowing who used it.

## 2. What the threat model says, and what survives

The five items, and whether multi-identity changes them.

| Today | After |
|---|---|
| **1. Anything else that can reach the port.** Every route but the liveness probe needs a credential; default bind is loopback | **Unchanged.** More credentials exist; none of them are optional |
| **2. A hostile page in the operator's browser.** `SameSite`, the CORS origin allow-list, and double-submit CSRF on cookie-authenticated mutations | **Unchanged.** All three are properties of the cookie, not of who holds it |
| **3. A stolen page context.** The session cookie is `HttpOnly`; the CSRF token is readable on purpose | **Unchanged** |
| **4. Brute force of the operator token.** ≥32 characters, SHA-256 compared in constant time, fixed delay per failure, **deliberately no lockout** — *"a lockout on a shared credential is a denial-of-service anyone who can reach the port can trigger"* | **The reasoning needs restating.** See §3 |
| **5. Not defended: host read access, transport interception, a hostile operator** | **Narrowed, not solved.** See §4 |

## 3. The lockout reasoning changes shape but not conclusion

Today's argument against a lockout is that the credential is *shared*: locking it
locks everyone, so anyone who can reach the port can deny service to the whole
system by failing to guess.

With per-identity credentials that argument weakens — a lockout would take out
one identity rather than all of them. But it does not invert:

- An attacker who can reach the port can enumerate identity names cheaply, or
  simply lock out every name they can guess, including `admin`.
- The failure delay already bounds guessing, and it costs an attacker the same
  whether credentials are shared or not.
- A lockout adds a state machine — who unlocks, how, and what happens when the
  only admin is locked out — whose failure mode is *no way in*, which is worse
  than the one it prevents.

**Conclusion: still no lockout.** Same ruling, different reason, and the reason
should be rewritten rather than left pointing at a shared credential that no
longer exists.

## 4. "A hostile operator" narrows, and must not be claimed as solved

Today a hostile operator is explicitly out of scope, and correctly so: there is
one credential and it does everything.

With tiers, a hostile *`viewer`* is meaningfully constrained, and that is real
value. But:

- A hostile `admin` is exactly as unconstrained as today. Tiers bound authority;
  they do not bound someone granted all of it.
- The operator token remains admin-equivalent by construction
  ([06-bootstrap.md](06-bootstrap.md)), so anyone holding it is outside the tier
  system entirely.
- Nothing here prevents a hostile operator from acting; the audit record makes it
  *attributable afterwards*, which is a different property and should be
  described as that one.

The honest statement for the amended threat model: **tiers reduce blast radius
and make actions attributable. They are not a defence against someone you gave
`admin` to.**
