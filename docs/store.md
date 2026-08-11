# State and secrets

Two databases under `MCORCH_DATA_DIR`, kept apart on purpose:

```
state.db      definitions, observed status, the change feed
secrets.db    secret material
```

Separate files because they have different handling rules. `state.db` is the
thing you copy around when debugging — attach it to a bug report, diff it, hand
it to somebody. Secret material must never travel that way, so it never lives in
the file that does.

Both sit behind interfaces (`Store`, `SecretStore`) that name no storage engine.
SQLite is today's single-host implementation, not the contract. Anything that
leaks SQLite specifics into those interfaces closes the door on a distributed
backend later, which is one of the three seams this project keeps open from day
one.

---

## What the store holds

Desired state (what you declared) and observed state (what the loop last saw)
live side by side under one name, which is what makes a row self-describing.

| Operation | Note |
|---|---|
| `putDefinition` | Create or replace |
| `deleteDefinition` | **Tombstones.** Marks terminating; does not remove |
| `purge` | Frees the name. Not exposed over the API |
| `putStatus` | Records an observation |
| `getServer` / `listServers` | Definition and status together |
| `listByDrainState` | How the loop finds drains to resume after a restart |
| `currentCursor` / `changesSince` | The change feed the reconcile loop and the event stream both read |

**Delete and purge are different operations, and only one is reachable from the
API.** A delete tombstones the definition and the reconcile loop starts a drain;
the name is freed only once `:core` has confirmed the containers are gone. `:core`
owns that guard, so `purge` is deliberately not exposed — an endpoint that could
reach past it would leave a running container with nothing describing it.

### `generation` moves only when the spec differs

A write whose spec matches the stored spec keeps the existing generation. This is
worth knowing because more than one thing keys off it:

- A dashboard re-sending a definition unchanged does not look like an edit.
- The reconcile loop's permanent-failure gate compares the observed generation
  against the definition's, so re-submitting an identical definition does **not**
  resume a server whose passes have stopped. See `docs/operating.md` note 1.

Note that the *recreate* decision is separate: it compares the container's spec
hash, not the generation. `api/API.md` §5 lists which fields feed that hash.

---

## Migrations

The on-disk schema is versioned and migrated forward on open. Applied migrations
are recorded in a `schema_migration` table with their description and timestamp,
and the log names each one as it runs — so a first start on an empty directory
prints six lines and a subsequent start prints none.

| Version | What it did |
|---|---|
| 1 | base schema: definitions, statuses, change log, revision sequence |
| 2 | project drain state out of stored status documents and index it |
| 3 | split a confirmed world save out of the save-request timestamp |
| 4 | reject rows written with no name, which SQLite's primary key allows |
| 5 | record a drain blocked on players as a block rather than as a failure |
| 6 | give every stored drain an explicit fault ledger of zero |

Several of those exist because a distinction that was once collapsed turned out
to matter: a save *requested* against a save *confirmed* (3), and a drain
*blocked* against a drain *failed* (5). Both are the difference between "wait"
and "act", and both are visible to a client — see `docs/server-lifecycle.md`.

**Downgrade is refused, not attempted.** Opening a store whose schema is newer
than the build understands fails with a message naming both versions, rather than
running against a shape it cannot read. Rolling the orchestrator back across a
migration therefore needs the data directory restored from before the upgrade.

A migration that fails leaves the store closed and the process stopped. There is
no partial-apply path.

---

## Secrets

Secret material is written by coordinates and **never read back**:

| Operation | Note |
|---|---|
| `put` | Write a key |
| `resolve` | Internal only — how the reconciler gets a value it needs |
| `contains` | Whether coordinates resolve, without reading |
| `removeKey` / `removeSecret` | |
| `listNames` / `listKeys` | Coordinates only, never values |

Over the API this is `PUT /api/v1/secrets/{name}/{key}` with the raw body as
`text/plain` or `application/octet-stream` — deliberately not JSON, so material
never passes through a JSON escape or gets bound into a parser's intermediate
strings. `GET` of a single key is routed to a `405` refusal rather than left to a
generic handler, so the refusal is a fact about the route table rather than an
oversight nobody notices.

A definition may only ever reference a secret by coordinates:

```yaml
passwordSecret:
  name: survival-01-rcon
  key: password
```

Nothing may be inline, anywhere, on any kind — `docs/schema.md` describes the
three guards that enforce it and the message they produce.

The Velocity forwarding secret is the strictest case: it travels through the
secret store and nowhere else — not a definition, not a log line, not a test
fixture.

### Consequences worth planning for

- **Writing an existing key replaces it silently.** The response reports
  `replaced` and the material's length, never its value. Check that field if you
  did not intend to overwrite; the previous value is unrecoverable.
- **A missing secret is a runtime failure, not a validation error.** A definition
  referencing coordinates that do not exist is perfectly valid — validation checks
  the reference's shape, not the store — and surfaces later as
  `FORWARDING_SECRET_UNAVAILABLE` or a failure to bring the container up. Write
  the secret before the definition that needs it.
- **Sessions are not in here.** Operator sessions live in memory, so a restart
  logs everyone out. That is deliberate: a session in the store would be a
  credential-shaped thing living in the database that gets copied around for
  backups and debugging, which is exactly what keeping the two files apart avoids.
