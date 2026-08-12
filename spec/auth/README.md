# Multi-identity authentication — specification

> **Status: proposed. Nothing here is implemented.**

Today every credential in this system is the same credential. This specifies
replacing that with identities that differ in authority.

It is specified **separately from the console** ([../README.md](../README.md))
because it is not a console feature. The console is its first consumer and the
reason it is being written now, but the change it requires is to the whole API's
authorization model — see [03-authorization.md](03-authorization.md), which is
the document that matters most here.

## Documents

| | |
|---|---|
| [01-today.md](01-today.md) | The single-credential model as it stands, and which parts of the threat model change |
| [02-model.md](02-model.md) | Identities, credentials, sessions, tiers |
| [03-authorization.md](03-authorization.md) | **Every existing endpoint acquires an authorization question.** The bulk of the work, and the part that is not about the console |
| [04-store.md](04-store.md) | The `:store` interface and its migration |
| [05-api.md](05-api.md) | Identity management endpoints, and what changes on `/auth/session` |
| [06-bootstrap.md](06-bootstrap.md) | The operator token, the first identity, and getting back in |

## Settled decisions

| Decision | Ruling | Why |
|---|---|---|
| Credential form | **Generated tokens, not chosen passwords** | Keeps the existing SHA-256-and-constant-time discipline, which is sound for ≥32 chars of entropy and is not sound for a password. Avoids the KDF question entirely |
| `MCORCH_API_TOKEN` | Stays, as a root credential that is **always Superuser-equivalent** | Scripts and CI depend on it, something has to create the first identity, and something has to get back in when identities are lost. Tiers do not bound it — see [06-bootstrap.md](06-bootstrap.md) |
| Sessions | Bind to an identity; stay in memory | `SessionRegistry` already holds them in a `ConcurrentHashMap` and they die with the process. Identity binding is a field, not a migration |
| Tenancy | **Still none.** Identities differ in tier, not in which servers they can see | Per-server visibility is a much larger feature and is not proposed here |
| Disabling | Separate from deletion | An audit record that names an identity must keep resolving after that identity stops being used |

## Open decisions

1. **Whether `Member` may read secret *coordinates*.** A server definition carries
   `{name, key}` references. They are not material, but they are a map of where
   material lives. §3.
2. **Whether a `Superuser` may create another `Superuser`.** Necessarily yes for
   bootstrap, but it means one compromised Superuser credential is unbounded. The
   alternative is that only the operator token may mint Superusers.

**Settled since the first draft:** the tier assignment for every existing route
([03-authorization.md](03-authorization.md) §2), the three tier names, and the
forced-`DELETE` semantics — now specified in
[`../termination/`](../termination/README.md).

## Sequencing

1. **The tier assignment** (open decision 1). Nothing can be built against an
   authorization model that has not been decided.
2. **Identity storage** — `:store` interface plus a forward-only migration
   ([04-store.md](04-store.md)).
3. **`Credential` becomes identity-bearing**, `SessionRegistry` binds identity,
   `GET /auth/session` reports it ([02-model.md](02-model.md), [05-api.md](05-api.md)).
4. **Route-level enforcement** for every existing endpoint
   ([03-authorization.md](03-authorization.md)).
5. **Identity management endpoints** ([05-api.md](05-api.md)).
6. **`api/API.md` §11 amended** — per-user roles and an audit log stop being
   absent.

The console's tier gate and `spec.console` land after step 4, not before: a tier
that only one endpoint honours is worse than no tier, because it reads as a
system-wide guarantee.
