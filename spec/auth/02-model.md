# 02 — The model

## 1. Identity

| Field | Notes |
|---|---|
| `name` | Operator-facing, unique, an RFC 1123 label like every other name in this system |
| `credentialDigest` | SHA-256, compared in constant time — the discipline `OperatorAuth` already uses |
| `tier` | `Member` / `Operator` / `Superuser` |
| `enabled` | Disabling must not require deleting |
| `createdAt` | |

**Disabling is separate from deletion** because an audit record names an identity
and has to keep resolving after that identity stops being used. A system where
revoking access destroys the record of what that access did is a system that
rewards deleting the evidence.

## 2. Credentials are generated tokens, not chosen passwords

This is the decision that keeps the rest simple.

`OperatorAuth` stores a SHA-256 digest and compares it in constant time. That is
**sound for a ≥32-character random token** and **not sound for a password a human
chose**: a fast hash over a low-entropy input is offline-crackable, and doing it
properly needs a KDF, an iteration count that has to be tuned and re-tuned, and a
per-credential salt.

PBKDF2 is in the JDK, so this would not add a dependency — but it would add a
parameter nobody in this project is positioned to keep current, to protect a
secret the system does not need to accept in the first place.

So: **the API generates the credential, the operator stores it.** Same rules as
`MCORCH_API_TOKEN` — minimum 32 characters, from `SecureRandom`, shown exactly
once at creation and never readable afterwards, exactly as
`api/API.md` already guarantees for secret material.

A human never types one twice: they paste it once into `POST /auth/session` and
hold a cookie after that, which is what the session mechanism is for.

> If chosen passwords ever become a requirement — an SSO-less deployment with
> humans logging in directly — that is a KDF decision and a separate change. It
> must not be arrived at by loosening the length rule on this field.

## 3. `Credential` becomes identity-bearing

Today:

```kotlin
internal sealed interface Credential {
    data object OperatorToken : Credential
    data class Session(...) : Credential
}
```

`OperatorToken` is a `data object` because there is only one of it. After this
change every credential resolves to **an identity and a tier**, and every call
site that today knows only *authenticated* has to come to know *who* — the tier
gate and the audit sink both need it, and neither can be added later without
touching the same call sites again.

The two presentation paths are unchanged in mechanism:

- `Authorization: Bearer …` — no CSRF, because a browser never attaches the
  header on its own and a cross-site request cannot carry one;
- `Cookie: mcorch_session=…` — CSRF required on mutating requests.

## 4. Sessions bind to an identity

`SessionRegistry.Session` gains the identity it was issued for. It stays in the
existing `ConcurrentHashMap`: sessions are already in-memory and die with the
process, so this is a field rather than a migration.

Everything else about them is unchanged — TTL, `HttpOnly`, `SameSite`, the
double-submit CSRF token, the `maxSessions` ceiling and oldest-first eviction.

**A session is not re-checked against its identity on every request.** It is
resolved once at creation. So disabling an identity does not invalidate its live
sessions unless something goes looking, which means disabling needs to sweep the
registry for that identity's sessions — otherwise "disabled" means "disabled at
next login", which is not what an operator revoking access believes they did.

## 5. Tiers

| Tier | Holds |
|---|---|
| `Member` | Non-destructive operations. Read-only |
| `Operator` | Non-destructive operations, plus limited creation and editing |
| `Superuser` | Full access |

`Member` ⊂ `Operator` ⊂ `Superuser`, and the ordering is total — which is what makes
`min(identity tier, server ceiling)` meaningful in
[`spec.console`](../03-command-policy.md) §4.

> `Superuser` is a placeholder name. It is used consistently throughout this
> specification so that renaming it later is one mechanical change rather than a
> hunt.

### 5.1 Where this is heading

Kubernetes' model is the reference for later work: roles as sets of verbs over
resource types, and bindings that attach a role to a subject. Three flat tiers is
deliberately *not* that — it is the smallest thing that answers the question the
console asks.

The migration path worth protecting is that a tier is expressible as a role: if
`Operator` is ever redefined as a named set of verb-resource pairs, nothing above
it should have to change. That argues for two things now, cheaply:

- **Tiers are compared, not switched on.** A call site asking `tier >= Operator`
  survives the change; one with a `when` over three names does not.
- **The tier is decided at the route table**, not scattered through handlers, so
  there is one place a role lookup would later replace.

What they mean for the console is in that document. What they mean for every
*other* endpoint is [03-authorization.md](03-authorization.md), and it is not
derivable from the console's meaning — a `Member` who may read a server's status
is not thereby someone who may read a secret's coordinates.

## 6. What this does not introduce

- **No tenancy.** Identities differ in tier, not in which servers they see. Two
  `Operator`s see the same fleet.
- **No groups, no inheritance, no per-resource grants.** A flat, totally ordered
  tier is the smallest thing that answers the question the console asks. Anything
  richer is a policy engine, and this project should not acquire one by accident.
- **No password reset, no email, no self-service.** An `Superuser` creates a
  replacement credential; there is no channel through which the system could
  deliver one anyway.
