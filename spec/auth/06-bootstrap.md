# 06 — Bootstrap and recovery

## 1. `MCORCH_API_TOKEN` stays

It is required today, has no default, and the API exits 78 without it —
`api/API.md` §12 is explicit that *"starting an unauthenticated API because a
variable was unset is not something this can do"*.

It stays, for three reasons that are each sufficient:

1. **Scripts, `curl` and CI hold it.** Removing it is a breaking change to an
   interface §12 documents.
2. **Something has to create the first identity.** A fresh install has an empty
   identity table ([04-store.md](04-store.md) §3) and no way in otherwise.
3. **Something has to get back in.** Every credential lost, the last admin
   disabled, a migration gone wrong — the recovery path is the host's
   environment, which is the one place an operator with physical or shell access
   always has.

## 2. It is always admin-equivalent, and that must be stated

The token is **outside the tier system**. It is not an identity with `tier:
admin`; it is the credential that exists before identities do, and it can do
everything.

This has to be said in `api/API.md` rather than left to inference, because the
natural reading of "the API has tiers now" is that tiers bound everyone. They do
not bound this.

Two consequences an operator has to be able to see coming:

- **Demoting yourself changes nothing if you also hold the env token.** An
  `admin` who sets their identity to `viewer` and still has `MCORCH_API_TOKEN` is
  still an admin, by a different door.
- **Host read access is admin access.** Threat model item 5 already says the
  token is an environment variable and that host access is not defended against.
  With tiers, that sentence acquires a sharper meaning: reading the host's
  environment does not get you *a* credential, it gets you the *unbounded* one.

The alternative — the env token being demotable, or scoped — was considered and
is rejected. A recovery credential you can lock yourself out of is not a recovery
credential.

## 3. First run

```
identity table empty
        │
        ├── bearer MCORCH_API_TOKEN ──► admin-equivalent, always
        │
        └── POST /api/v1/identities ──► first real identity, credential returned once
```

No default identity is created by the migration, and nothing generates a
credential the operator did not ask for. [04-store.md](04-store.md) §3 has the
reasoning: a credential minted during a migration is one printed into a log or
lost, and a lost admin credential is one nobody holds and nothing revokes.

## 4. Recovery

| Situation | Way back |
|---|---|
| An operator lost their credential | An `admin` rotates it — [05-api.md](05-api.md) §2 |
| The last admin was disabled | Refused by `LAST_ADMIN` before it can happen; if it happened anyway, the env token |
| Every credential lost | The env token |
| The env token lost | Set a new one and restart. It is compared as a digest of whatever the variable currently says, so changing it is changing it |

The last row is worth stating because it is the one people assume is
unrecoverable. There is no stored copy of the operator token to be out of sync
with — the running process holds a digest of what the variable said at startup,
so a new value plus a restart is a complete reset of that credential.

Identity credentials are **not** like that: they are digests in the store, and
there is no material anywhere to recover. Rotation is the only path, and it needs
an `admin` or the env token.

## 5. What this costs, stated plainly

The env token is a permanent, unbounded, non-revocable-from-inside credential
that lives in the host environment. Every deployment has one, by construction.

That is the same exposure the system has today — nothing here makes it worse. But
today it is *the* credential and nobody could mistake its power, whereas after
this change it is one credential among several and the only one the tier system
does not describe. **The risk introduced is misunderstanding, not exposure**, and
the mitigation is documentation rather than mechanism: §2 exists so that
`api/API.md` says it outright.
