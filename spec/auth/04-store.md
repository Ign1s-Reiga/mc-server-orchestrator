# 04 — Storage

## 1. What is new, and what is not

**Identities are persisted.** They outlive the process, and an orchestrator that
forgot who its operators were on restart would fall back to the bootstrap
credential every time.

**Sessions are not.** `SessionRegistry` already holds them in a
`ConcurrentHashMap` and they die with the process. That is a deliberate property
worth keeping: a restart logs everyone out, which is the safe direction, and
persisting them would mean a stolen cookie survives the event most likely to have
been the response to the theft.

So this is one new persisted concept, not two.

## 2. The interface

Behind `Store`, with no storage-engine specifics leaking — the third of the
distribution seams CLAUDE.md names, and the rule that the interface must be
satisfiable by a distributed backend later.

```
putIdentity(identity)          create or replace
identity(name): Identity?      by name
identities(): List<Identity>   all of them, for the management endpoints
removeIdentity(name): Boolean  whether it was there
```

Two constraints on the shape:

- **The digest is stored, never the credential.** The interface takes an
  `Identity` carrying `credentialDigest`; nothing in it accepts or returns
  material. This is the same rule `SecretStore` follows from the other side, and
  it means a store implementation cannot leak a credential it never receives.
- **No query language.** `identities()` returns all of them. There are tens of
  operators at most, `api/API.md` §11 already declines pagination at this scale,
  and a filter parameter is the beginning of a query interface that a distributed
  store would then have to satisfy.

## 3. Migration

Forward-only, matching how the on-disk schema is already versioned.

The table is new, so nothing existing is reshaped and no data can be lost — which
makes this the easy half. The hard half is what an **empty** table means on first
run after upgrade, and it has exactly one safe answer: it means *no identities
exist yet*, and the bootstrap credential is the only way in until one is created
([06-bootstrap.md](06-bootstrap.md)).

It must **not** mean "create a default Superuser identity with a generated
credential", because a credential generated during a migration is a credential
printed into a log or lost — and if it is lost, it is a Superuser credential nobody
holds and nothing revokes.

## 4. What the migration test has to prove

Per the `add-server-kind` procedure's standard for store changes, and one item
beyond it:

- an existing database opens after the migration with every server definition and
  every observed status intact;
- the identity table exists and is empty;
- **the API still starts and still authenticates the bootstrap token** against
  that empty table. An upgrade that leaves an operator unable to reach their own
  orchestrator is the failure this whole document is trying not to cause.
