# mcorch dashboard API — v1

The contract the operator dashboard is written against. This document is the
specification; `:api` is one implementation of it. If the two disagree, that is a
bug in one of them — say which in the issue.

There is no OpenAPI document. This file is hand-written because most of what a
client needs to get right here is not expressible in a schema: which write is
declarative, why a delete answers 202, what `TERMINATING` obliges the UI to show,
and which of two null policies applies where. A generated `paths` section would
duplicate the easy half and drop the half that matters. The TypeScript types at
the end are the machine-readable part.

- Base path: `/api/v1`
- Content type: `application/json; charset=utf-8` on every response with a body
- Transport: plain HTTP/1.1. Put a TLS terminator in front of it unless it is
  bound to loopback.

---

## 1. The idea a client has to hold

**This API is a thin edge over a declarative core.** Every mutation writes
*desired state* and returns. Nothing in it starts, stops or inspects a container.

Creating a server writes a definition; the reconcile loop pulls the image,
creates the sandbox and starts the container some seconds later. Changing a spec
makes the loop drain the running server and replace it. Deleting one tombstones
the definition, and the loop runs the drain protocol — evacuate players, confirm
a world save, *then* stop — before the name is released.

So:

- **A 2xx means the request was recorded, not that the world has changed.** Watch
  `status` and `display.state` for what actually happened.
- **There is no stop, kill, force or restart endpoint, and there will not be
  one.** An endpoint that could stop a container is an endpoint that could stop
  one with players on it. See §11.
- **A deleted server does not disappear.** It comes back from `GET` with
  `metadata.terminating: true` until `:core` has finished with it. A dashboard
  that removes the row on `202` is showing a stop that has not happened.
- **Neither does a server the store cannot read.** A row whose stored state will
  not decode is reported as unreadable, never omitted — see §6. Absence means
  purged, and only purged.

---

## 2. Authentication

### Threat model

Single host, **three tiers of user**, no tenancy. In priority order this defends
against:

1. **Anything else that can reach the port.** Every route except `/healthz` and
   the CORS preflight requires a credential. The default bind is `127.0.0.1`.
2. **A hostile page in the operator's browser.** Three independent controls:
   `SameSite` on the cookie, an origin allow-list checked before any handler
   runs, and a double-submit CSRF token on every cookie-authenticated mutation.
3. **Script running in the dashboard's own page.** The session cookie is
   `HttpOnly`, so injected script cannot read or exfiltrate it. The CSRF token is
   readable on purpose — it is not a credential on its own.
4. **Guessing a credential.** Every credential is at least 32 characters,
   compared as a SHA-256 digest in constant time, with a fixed delay on every
   failure. **No lockout**, still: an attacker who can reach the port can
   enumerate or guess identity names cheaply, so a lockout would hand them a
   denial of service against every name they can think of — including the last
   superuser. The delay bounds guessing at the same cost either way, and a lockout
   adds a state machine whose failure mode is *no way in*.
5. **An operator exceeding what they were granted.** Every route declares the
   tier it requires, and a route registered without one does not compile. See
   §2.1.

Explicitly **not** defended against: an attacker who can read the host's
environment or process table (`MCORCH_API_TOKEN` is an env var), transport
interception (this server speaks plain HTTP), and **a hostile superuser**.

Tiers reduce blast radius and make actions attributable. They are not a defence
against somebody you granted `superuser` to, and they do not bound the operator
token at all — see §2.2.

### 2.1 Tiers

| Tier | Holds |
|---|---|
| `member` | Non-destructive operations. Read-only |
| `operator` | Reads, plus creating and editing servers |
| `superuser` | Everything, including deleting a server, writing secrets, and managing identities |

Totally ordered, so a tier holds everything the ones below it do. `GET
/api/v1/auth/session` reports yours — **read it on page load and render only what
it permits**, rather than discovering the limits from a scatter of `403`s.

Two of the assignments are data-safety decisions rather than access-control ones,
because every mutating endpoint can request a drain and a drain is how a Minecraft
server stops:

- **`DELETE /api/v1/servers/{name}` is `superuser`.** It is the endpoint that ends
  a server.
- **`PUT /api/v1/servers/{name}` is `operator`**, knowingly. An edit that changes
  the spec drains the running server and replaces it — so an `operator` can cause
  a fleet-wide restart by editing several manifests, and nothing rate-limits that.
  It is not `superuser` because the replacement still drains: nobody is
  disconnected, the world is saved first, and a careless `PUT` costs a restart
  rather than data.

A tier below what a route requires gets **`403 FORBIDDEN`** carrying
`requiredTier`. That is deliberately not `401`: the credential is fine and there is
nothing to log in again *as*, so a client that retries the login on it loops.

### 2.2 The operator token is outside the tier system

`MCORCH_API_TOKEN` is not an identity that happens to hold `superuser`. It exists
before any identity does, it cannot be demoted, and it is how you get back in when
every credential is lost. It reports itself as `<operator-token>`.

Two consequences worth seeing coming:

- **Demoting yourself changes nothing if you still hold it.**
- **Host read access is superuser access.** Threat-model item 5 above already says
  the token is an environment variable; with tiers, that sentence means reading
  the host's environment does not get you *a* credential, it gets you the
  *unbounded* one.

### Two credentials

| | Header | CSRF needed | For |
|---|---|---|---|
| Bearer credential | `Authorization: Bearer <token>` | no | scripts, `curl`, CI. The operator token or any enabled identity's |
| Session cookie | `Cookie: mcorch_session=…` | yes, on mutations | the SPA |

The bearer exemption is not a convenience: a browser never attaches an
`Authorization` header on its own, so a cross-site page cannot produce that
request at all and a CSRF token would add nothing.

The cookie exists because `EventSource` cannot set headers — the live stream
(§8) can only authenticate by cookie. Given that, the SPA should use the cookie
everywhere: an `HttpOnly` cookie is a credential injected script cannot lift,
whereas an operator token in `localStorage` is one it can post anywhere.

### `POST /api/v1/auth/session`

Exchanges a credential for a session. The only route where a credential is
*established*, so it checks it directly before doing anything.

- Request: `Authorization: Bearer <credential>`. No body. Accepts the operator
  token or any **enabled** identity's credential. It is never accepted in a body
  or query string — a query string is logged by every proxy in the world.
- `200`:
  ```json
  { "authenticated": true, "method": "session",
    "identity": "rin", "tier": "operator",
    "csrfToken": "9Xk…", "expiresAt": "2026-07-28T22:15:30Z" }
  ```
  plus `Set-Cookie: mcorch_session=…; Path=/; Max-Age=43200; HttpOnly;
  SameSite=Strict[; Secure]`
- `401 UNAUTHENTICATED` — wrong or missing token.

### `GET /api/v1/auth/session`

Who am I, and which CSRF token should I be sending. Call this on page load.

- `200` with the same shape. `method` is `"session"` or `"bearer"`; for a bearer
  caller `csrfToken` and `expiresAt` are `null`. `identity` and `tier` are always
  present — **this is the call a dashboard reads on load to decide what to
  render**.
- `401` if the session is unknown or expired — the SPA's cue to show a login.

### `DELETE /api/v1/auth/session`

- `204`, and `Set-Cookie` with `Max-Age=0`.
- Mutating, so a cookie caller must send `X-CSRF-Token`. A cross-site page that
  could log the operator out at will is a nuisance attack.
- `400` if the caller authenticated with a bearer token: there is no session.

### CORS

| `Origin` | Result |
|---|---|
| absent | allowed, no CORS headers (a script, not a browser) |
| matches the request's `Host` | allowed, no CORS headers (the normal deployment) |
| in `MCORCH_API_ALLOWED_ORIGINS` | allowed, `Access-Control-Allow-Origin: <that origin>` + `Access-Control-Allow-Credentials: true` + `Vary: Origin` |
| anything else | `403 ORIGIN_NOT_ALLOWED`, **before any handler runs** |

Never `*` — a browser refuses to combine a wildcard with credentials, and this
API is credentialed by construction. `OPTIONS` on any path answers `204` with
`Allow-Methods`, `Allow-Headers` (`Authorization, Content-Type, If-Match,
X-CSRF-Token, Last-Event-ID`), `Expose-Headers` (`ETag, Location, Retry-After`)
and `Max-Age: 600`.

A cross-site dashboard (a genuinely different site, not just a different port)
additionally needs `MCORCH_API_COOKIE_SAMESITE=None`, which requires
`Secure`, which requires TLS.

---

## 3. Errors

One shape, always:

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "the definition has 3 problems",
    "retryable": false,
    "violations": [
      { "field": "spec.resources.heap.max",
        "problem": "must leave headroom below the container memory limit: …",
        "location": { "source": "request-body", "line": 12, "column": 9 } }
    ],
    "conflict": null
  }
}
```

`violations`, `conflict` and `unreadable` are `null` unless the code carries
them. Branch on `code`, never on `message`.

`SERVER_UNREADABLE` carries:

```json
{ "error": { "code": "SERVER_UNREADABLE", "retryable": false, "unreadable": {
    "name": "survival-03", "part": "DESIRED", "reason": "…", "retryable": false
} } }
```

`part` is `DESIRED` (the definition) or `OBSERVED` (the status). It is a 500
because no retry and no change to the request will fix it — a human repairs the
row — and a distinct code because the remedy is specific. **It is not evidence
about the container.** The server is very probably running exactly as it was;
what is broken is the record of it. A `DESIRED` row can be repaired through this
API by `PUT`ting a valid definition with `If-Match: *`.

| code | status | carries | meaning |
|---|---|---|---|
| `BAD_REQUEST` | 400 | | malformed query, bad path segment, non-UTF-8 body |
| `UNAUTHENTICATED` | 401 | | no credential, or not a valid one |
| `CSRF_REQUIRED` | 403 | | cookie-authenticated mutation with no `X-CSRF-Token` |
| `CSRF_INVALID` | 403 | | the token does not match the session |
| `ORIGIN_NOT_ALLOWED` | 403 | | cross-origin request from an unconfigured origin |
| `FORBIDDEN` | 403 | `requiredTier` | authenticated, and below the tier the route needs. **Not** a reason to log in again |
| `NOT_FOUND` | 404 | | no such server, secret or endpoint |
| `IDENTITY_NOT_FOUND` | 404 | | no identity holds that name |
| `CONSOLE_COMMAND_REFUSED` | 409 | | a console command refused at every tier — see §9.6 |
| `CONSOLE_NOT_APPLICABLE` | 409 | | the console was asked of a `VelocityProxy`, which has no RCON |
| `CONSOLE_UNAVAILABLE` | 503 | `Retry-After` | the server cannot answer yet. **Retryable**, and nothing was sent |
| `CONSOLE_TIMEOUT` | 504 | | the command **ran or may have run** and no reply arrived. **Do not retry** |
| `FORCE_REFUSED` | 409 | | a forced stop that could be made safe — an unacknowledged population, an unusable `saveTimeout`, a grace period too short to be the save it would become |
| `IDENTITY_EXISTS` | 409 | | `POST /identities/{name}` never overwrites |
| `LAST_SUPERUSER` | 409 | | the change would leave no enabled superuser |
| `METHOD_NOT_ALLOWED` | 405 | `Allow` | |
| `SECRET_NOT_READABLE` | 405 | `Allow` | reading secret material. Never possible. |
| `CONFLICT` | 409 | `conflict`, `ETag` | a write lost a race or hit an integrity rule |
| `PAYLOAD_TOO_LARGE` | 413 | | body over `maxBodyBytes` (1 MiB by default) |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | | wrong `Content-Type` |
| `VALIDATION_FAILED` | 422 | `violations` | the document parsed but is not a valid definition |
| `PRECONDITION_REQUIRED` | 428 | | `PUT` with no `If-Match` |
| `INTERNAL` | 500 | | a bug, or a permanent store failure |
| `SERVER_UNREADABLE` | 500 | `unreadable` | the store holds this row and cannot decode the part the request needed |
| `STORE_UNAVAILABLE` | 503 | `Retry-After` | the store could not be reached. **Retryable.** |
| `STREAM_LIMIT` | 503 | `Retry-After` | too many event streams open. **Retryable.** |

`retryable` is true only for the two 503s, and it is the store's own
classification (`StoreException.retryable`) rather than a guess. A `500` is never
worth retrying. Store failure messages are not forwarded — they can name a file
path or a SQL statement.

---

## 4. Optimistic concurrency

Every read of a server carries `ETag: "<resourceVersion>"`, and the same value is
in `metadata.resourceVersion`.

- **`PUT` requires `If-Match`.** Without it: `428 PRECONDITION_REQUIRED`. Send the
  `ETag` you read. `If-Match: *` means "overwrite whatever is there, but it must
  exist" and is the deliberate override.
- **`POST` never overwrites.** Two operators pasting the same YAML: one gets
  `201`, the other `409 ALREADY_EXISTS`.
- **`DELETE` accepts `If-Match` and does not require it.**

Requiring `If-Match` on `PUT` is not pedantry. Two dashboard tabs editing the same
server is the ordinary case, and a spec change here is not a database row — it
makes the loop drain a running server and replace it. Discarding somebody's edit
is bad; discarding it *and* restarting a server full of players to do so is worse.

A conflict is a **409**, including a version mismatch that RFC 9110 would call a
412. One branch in the client for "somebody got there first", with
`error.conflict.reason` saying which reason:

```json
{ "error": { "code": "CONFLICT", "conflict": {
    "name": "survival-01",
    "reason": "VERSION_MISMATCH",
    "currentResourceVersion": "42",
    "explanation": "the stored definition has changed since the version you sent in If-Match…"
} } }
```

`reason` ∈ `ALREADY_EXISTS | VERSION_MISMATCH | NOT_FOUND | TERMINATING |
NOT_DELETED | KIND_MISMATCH | DEFINITION_CHANGED`. `ETag` on the 409 carries the
current version too.

The recovery loop is always: re-read, re-apply the edit to what is stored now,
write again with the new `ETag`.

---

## 5. Sending a definition

`POST /api/v1/servers`, `PUT /api/v1/servers/{name}` and `POST /api/v1/validate`
all take a definition document. **The type is `DefinitionInput` in §14, not
`Definition`** — the two are different and it matters: nearly everything the
parser defaults is optional on the way in, so a four-field document validates,
while what comes back has every default resolved. `Definition` is assignable to
`DefinitionInput`, so a fetched definition can be edited and sent back with no
cast.

An explicit `null` is a **violation**, not "use the default" — `spec.storage:`
with nothing under it is a mistake worth reporting rather than a request for the
default. Omit the key. `JSON.stringify` drops `undefined` properties, so an
optional property left unset is already correct.

Unknown fields are rejected with a violation naming the field and, where the
schema can guess, a `did you mean …?`.

**Accepted `Content-Type`:** `application/json`, `application/yaml`,
`application/x-yaml`, `text/yaml`, `text/x-yaml`, `text/plain`, or absent.

**JSON and YAML go through one parser and need no separate code path.** YAML 1.2
is a strict superset of JSON, so `JSON.stringify(definition)` is parsed by the
same validator, produces the same dotted field paths, reports *every* problem in
one answer, and gives line/column positions into the JSON text the client sent.
The corollary, stated rather than discovered: a body labelled `application/json`
that is YAML but not JSON is accepted. This validates the document; it does not
police the syntax it was told to expect.

Violations attach to fields, which is what a form needs:

```json
{ "field": "spec.network.port", "problem": "must be between 1 and 65535, found 0",
  "location": { "source": "request-body", "line": 20, "column": 11 } }
```

`field` is a dotted path into the document as sent (`spec.resources.heap.max`),
never a class or property name. `location` may be `null` for a problem with no
single position; `source` is always `"request-body"`.

### Which edits replace the container

Some fields describe the container, and changing one cannot be applied to a
container that already exists — the loop drains the server and builds a new one.
Others are read while the loop works and take effect on the next pass. A client
that offers an edit form should say which kind it is about to perform, because
the first kind stops the server for minutes and moves players off it.

The decision is a hash comparison, not a generation check
(`core/src/main/kotlin/mcorch/core/Reconciler.kt:560`): the observed container
carries the hash it was built from, and any difference from the desired hash is
`DrainCause.REPLACEMENT`. The inputs are exactly these.

**`PaperServer`** — from `core/src/main/kotlin/mcorch/core/paper/PaperWorkload.kt`:

| Reshapes the container |
|---|
| `spec.image` |
| `spec.paper.minecraftVersion`, `spec.paper.build` |
| `spec.resources.memory`, `spec.resources.cpu` |
| `spec.resources.heap.max`, `spec.resources.heap.min` |
| `spec.storage.mode`, `spec.storage.mountPath`, `spec.storage.volume.name` |
| `spec.network.port`, `spec.network.hostPort` |
| `spec.network.rcon` — its port and secret coordinates. Always present |
| `spec.console.maxTier` — the highest tier the console accepts here. Clamps; never grants |
| `spec.maxPlayers` |
| *whether a proxy claims this server* — see below |

**`VelocityProxy`** — from
`core/src/main/kotlin/mcorch/core/proxy/VelocityWorkloadPlanner.kt`:

| Reshapes the container |
|---|
| `spec.image`, and the pinned Velocity build |
| `spec.resources.memory`, `spec.resources.cpu` |
| `spec.resources.heap.max`, `spec.resources.heap.min` |
| `spec.network.port`, `spec.network.hostPort` |
| `spec.control.port`, `spec.control.hostPort`, `spec.control.tokenSecret` |
| `spec.forwarding.mode`, `spec.forwarding.secret` |
| `spec.maxPlayers` |
| the control-plugin protocol version — orchestrator-side, not yours to set |

Everything else is absent from the hash and applies without a replacement. The
useful ones are the timings: `spec.lifecycle.drain.*`,
`spec.lifecycle.stopGracePeriod`, `spec.lifecycle.startupTimeout`, and on a proxy
`spec.backends.selector`, `spec.backends.fallback` and `spec.backends.drain.*`.
Those are read when the loop needs them rather than baked into a container, so an
operator can retune a drain on a running fleet.

**A label can reshape a container it is not on.** `metadata.labels` is not a hash
input, but a `PaperServer`'s hash carries `forwarding.secret` *only while a proxy
claims it* — that is deliberate, so that declaring the first proxy does not
recreate every unrelated server. The consequence is that adding a label which
crosses a `VelocityProxy`'s `spec.backends.selector`, or editing the selector
itself, changes the **backend's** hash and drains it. Two documents are involved
and only one of them was edited, so a client that reasons "labels are metadata,
this is safe" will be wrong exactly when a server joins or leaves a fleet.

### Choices that are hard to reverse after creation

Two of the fields above are effectively creation-time on a server that holds a
world, and a create form is the last cheap moment to get them right.

**`spec.network.rcon` on persistent storage.** Changing it reshapes the
container, and reshaping requires a drain, and a drain on persistent storage
cannot finish without RCON to confirm the world reached disk. So if the running
container has stopped answering, an edit to `rcon` cannot rescue it — the edit is
accepted, the replacement drain starts, and it stalls permanently with the
original container still running and still joinable.

RCON is standard, so this can no longer be reached by *declaring* a server
without it. It is still reachable whenever the running container stops answering:
a wedged main thread, a long world-generation pass, or a password it does not
have. The way out is not another edit — see `docs/operating.md` note 1.

**`metadata.labels` on a server meant for a fleet.** A label added at creation
costs nothing; the same label added later drains the server, for the reason in
the previous section. Offering fleet membership on the create form is worth more
than offering it on the edit form.

---

## 6. Servers

### The resource

```jsonc
{
  "name": "survival-01",
  "kind": "PaperServer",
  "apiVersion": "mcorch.dev/v1alpha1",

  // Exactly what you POST or PUT back. See the null policy below.
  "definition": { "apiVersion": "…", "kind": "PaperServer",
                  "metadata": { "name": "survival-01", "labels": { … } },
                  "spec": { … } },

  // The store's bookkeeping. Never sent back.
  "metadata": {
    "generation": 3,              // moves only when the spec changes
    "resourceVersion": "42",      // moves on every write. This is the ETag.
    "createdAt": "…", "updatedAt": "…",
    "deletedAt": null,            // set once a delete has been requested
    "terminating": false
  },

  "status": { … } | null,         // null until the loop has looked at it
  "statusMeta": { "resourceVersion": "…", "recordedAt": "…" } | null,

  // Why `status` is null, when the answer is not "nothing has been observed".
  // Null in the ordinary case.
  "unreadable": { "part": "OBSERVED", "reason": "…", "retryable": false } | null,

  "caughtUp": false,              // status.observedGeneration === metadata.generation
  "neverObserved": true,          // status === null AND unreadable === null
  "display": { … }                // §7
}
```

#### `status: null` has two meanings. Use `neverObserved`.

The store holds an observation it cannot decode as *no readable observation*, so
`status` is null for both "the loop has not looked at this yet" and "what the
loop wrote down is corrupt". They call for opposite things from an operator —
one you wait out, one you fix — so they are distinguishable:

| | `status` | `unreadable` | `neverObserved` | `display.state` |
|---|---|---|---|---|
| not observed yet | `null` | `null` | `true` | `PENDING` |
| observation will not decode | `null` | set | `false` | `UNREADABLE` |
| observed | object | `null` | `false` | from the phase |

A client that only ever tested `status === null` keeps working and keeps being
wrong in the second row; test `neverObserved` instead.

`unreadable.reason` is operator-facing text on the same terms as
`status.failure.message`: it names the server and what about the stored form was
rejected, and it carries no stack trace, no class name and no storage-level
detail.

`retryable` is false for anything that failed to decode — the stored bytes will
say the same thing next time — and from the embedded store it is false *by
construction*: a read that failed for a retryable reason is re-raised rather than
attached to a row, because "the thing that reads this is unreachable" describes
the read and not the record. Treat `true` as a possibility a networked backend
could honestly report, not as something to expect today.

#### There are two kinds, and `kind` discriminates every shape

`ServerResource.definition` and `ServerResource.status` are **unions**, tagged by
`kind`. Every endpoint returns both kinds from the same routes — there is no
`/proxies` — so a client that assumed one shape needs a discriminant now:

```ts
if (server.status?.kind === 'VelocityProxy') { /* backends, control */ }
else if (server.status?.kind === 'PaperServer') { /* storage */ }
```

| | `PaperServer` | `VelocityProxy` |
|---|---|---|
| spec has | `paper`, `storage`, `eulaAccepted` | `forwarding`, `backends`, `control` |
| status has | `storage` | `backends`, `control` |
| `display.proxy` | `null` | populated |

A proxy has **no `storage`**, in the spec or the status, and no way to ask for
one: it holds no world, and a proxy that claimed to would become a container the
orchestrator could never stop, because it has no save to confirm. Its absence is
structural, not an omission to default.

Everything else is common: `metadata`, `display`, `unreadable`, `caughtUp`,
optimistic concurrency, deletion semantics, the event stream. A proxy is drained
before it is stopped exactly like a server.

#### Two null policies, and why

`definition` **omits** absent optional fields. Everything else renders them as an
explicit `null`.

That asymmetry buys the property that matters most: **what `GET` returns under
`definition` is valid input to `POST` and `PUT`, unchanged.** The schema treats an
explicit `null` as a violation rather than as "unset" — `spec.storage:` with
nothing under it is a mistake worth reporting, not a request for the default — so
a definition rendered with explicit nulls would come straight back as a 422 the
first time a dashboard round-tripped it. Nothing else in a response is ever sent
back, so nothing else pays that price, and a fixed key set is worth more to a
TypeScript client than symmetry is.

Affected: `paper.build`, `network.hostPort`, `resources.cpu`,
`storage.volume.size`, `placement.node`, and `metadata.labels` when empty.

`network.rcon` is **not** affected. RCON is standard, so the block is always
present and always carries a `passwordSecret`.

This is what makes `Definition` assignable to `DefinitionInput` in §14 —
`const draft: DefinitionInput = server.definition` compiles. What comes *out* is
always richer than the minimum that goes *in*, never the other way round.

`definition.spec` is the **effective** spec: the parser resolves every default, so
what comes back is what the reconciler acts on, not what the operator typed. A
`minimal.yaml` with four fields returns a spec with all of them.

### `GET /api/v1/servers`

```json
{ "cursor": "17", "count": 2, "items": [ /* resources */ ],
  "unreadableCount": 1,
  "unreadable": [ { "name": "survival-03", "part": "DESIRED",
                    "reason": "…", "retryable": false } ] }
```

Sorted by name. `cursor` is the change-feed position to open the stream from
(§8); it is read **before** the list, so a definition written between the two
reads appears in the stream rather than being missed by both.

#### `unreadable` — rows there is a name for and nothing else

A server whose stored **definition** will not decode cannot be a resource: there
is no spec, so there is nothing to render short of inventing one. It appears in
this second array instead of in `items`.

It appears at all because **absence means something**. Omitting the row would be
indistinguishable from the server having been purged, and a dashboard derives
removal from absence — so a bad row would silently report a deletion that never
happened, on a server that may well still be running with players on it. Render
these as rows with an error badge, or as a banner; do not drop them.

`unreadable` is **never filtered**. A row with no readable definition cannot
answer "is it `READY`", "does it carry this label" or "is it terminating", so any
filter would drop it — and dropping it is the mistake above. Its own array is
what keeps it out of `items` without hiding it.

`name` is the raw stored string, not a validated resource name: the name can
itself be why the row will not read, and a shape that could not hold an invalid
one would throw away the only identifying thing left.

##### `name` can be `null`, and such a row is the one you can least act on

A record with **no name at all** is one of the ways a row becomes unreadable —
SQLite permits `NULL` in a rowid table's primary key, so a hand-written or
legacy row without one is possible. `null` is how the store genuinely holds it,
and a placeholder here would invent an identity nothing could act on anyway.

What a client must handle:

- **Render it.** It is a real row and an operator has to be told. Show it as an
  unidentifiable entry with its `reason`; it counts in `unreadableCount`.
- **Key it by something other than `name`.** Two nameless rows are
  indistinguishable to this API, so key your list by index or by `reason`.
- **Offer no action on it.** Every repair path this API has names a server —
  `PUT`, `DELETE`, `GET /{name}` — so a nameless row cannot be fetched, repaired
  or deleted through the API at all. It has to be fixed in the store. Say that
  rather than showing a button that cannot work.
- **Expect removals to go quiet.** See §8.

One nameless row does not affect any other row: the rest of `items` and
`unreadable` are exactly as they would be.

One bad row costs its own server and nothing else. This endpoint does not fail
because of one.

Query parameters, all optional:

| parameter | form | meaning |
|---|---|---|
| `labelSelector` | `tier=survival,region=eu-west` | AND of equalities. `400` if a term has no `=`. |
| `state` | repeatable, e.g. `state=READY&state=DRAINING` | `display.state` |
| `terminating` | `true` \| `false` \| `any` (default) | |

### `GET /api/v1/servers/{name}`

`200` with the resource and `ETag`. `404 NOT_FOUND` if the name is unknown.
`400 BAD_REQUEST` if the segment is not a usable resource name.

A server whose **observation** will not decode still answers `200`, with
`unreadable` set and `display.state: "UNREADABLE"` — clicking a row in the fleet
table never produces an error the list did not warn you about.

A server whose **definition** will not decode answers `500 SERVER_UNREADABLE`,
because there is no resource to send. Not `404`: that would say the server is
gone when it may still be running.

> **Recorded divergence.** The tenth drain audit specified that this endpoint
> keep raising for either kind of bad row, on the principle that a read naming
> one server wants the failure rather than a snapshot with a hole in it. That
> principle is right, and it is why `:store`'s own `getServer` is strict — but it
> is aimed at a caller with nowhere to put the fact, which would then silently
> report "no observation". This API has somewhere to put it: `unreadable` and the
> `UNREADABLE` badge say exactly what is missing and why. Raising as well would
> mean the list shows a row that 500s when clicked, which teaches operators that
> the dashboard is broken rather than that a row is.
>
> Where the principle bites, it is preserved: `/status` still raises, because it
> exists to serve an observation and cannot serve one it cannot read; and the
> failure is `SERVER_UNREADABLE`, never `NOT_FOUND`, so nothing ever claims a
> possibly-running server is gone.

### `GET /api/v1/servers/{name}/status`

The observation on its own, for a cheap poll of one server.

```json
{ "name": "…", "observedGeneration": 3, "generation": 3, "caughtUp": true,
  "recordedAt": "…", "resourceVersion": "…", "status": { … } }
```

`404` if the name is unknown **or** if nothing has been observed yet — the two
are distinguishable by the message, and by `GET /api/v1/servers/{name}` returning
`status: null`.

`500 SERVER_UNREADABLE` if an observation exists and will not decode. This
endpoint cannot serve an observation it cannot read, and answering `404` would
report it as never observed.

### `POST /api/v1/servers`

Create. Body: a definition (§5).

- `201` + `Location: /api/v1/servers/{name}` + `ETag`, body is the resource.
- `409 CONFLICT` — `ALREADY_EXISTS`, `TERMINATING` (a delete is in flight for that
  name), or `KIND_MISMATCH`.
- `422 VALIDATION_FAILED`.

Nothing is running yet. The resource comes back with `status: null` and
`display.state: "PENDING"`.

### `PUT /api/v1/servers/{name}`

Replace. Body: a definition. `If-Match` required (§4).

- `200` + `ETag`, body is the resource.
- `409` — `VERSION_MISMATCH` or `TERMINATING`.
- `422` — including `metadata.name` if the body's name disagrees with the path.
  Renaming is a create and a delete, not an edit: the old server has to be
  drained before its name is released.
- `428` — no `If-Match`.

**A spec change is a recreate.** If the running workload no longer matches, the
loop drains the server before replacing it. Which fields do that, and which apply
in place, is listed in §5 — worth showing the operator before the `PUT`, since a
recreate moves players off a running server.

A metadata-only change does not move `generation`, but **that is not the same as
causing no drain**: the recreate decision compares the container's spec hash, not
its generation, and a `metadata.labels` edit that crosses a `VelocityProxy`'s
`spec.backends.selector` changes the *backend's* hash. See §5.

### `DELETE /api/v1/servers/{name}`

**A drain request, not a stop.** Returns immediately.

```json
{ "accepted": true,
  "message": "the delete was recorded. The reconcile loop drains the server — evacuating players and confirming a world save — before anything is stopped, and frees the name only when that has finished. Poll this server, or watch the event stream, until it reports 404 NOT_FOUND",
  "server": { /* the resource, now terminating */ } }
```

- `202 Accepted` + `ETag`. Repeating it is a no-op that answers `202` again.
- `404` if the name is unknown. `409` if `If-Match` was sent and does not match.
- **`?force=true` stops it anyway.** `superuser` only, and it is not a way to make
  an ordinary delete faster — it is the way out of a delete that **cannot
  finish**. See below.

What a client should do afterwards:

1. Keep the row. Render `display.state: "TERMINATING"` and, once the loop starts,
   `status.drain.state` — `SEALED`, `TRANSFERRING`, `SAVING`, `DEREGISTERED`,
   `STOPPING`.
2. Poll the resource, or watch the stream for the `removed` event.
3. The row is gone when `GET` returns `404`. That happens when `:core` has
   confirmed the containers are gone and freed the name — **the API cannot do it
   and does not expose a way to.**
4. `status.drain.state: "DRAIN_FAILED"` means the drain aborted **and the server
   is still running**. There is no edge from there to a stop within the drain —
   `?force=true` is the edge, and it is an operator decision rather than something
   the loop reaches on its own.

#### `DELETE /api/v1/servers/{name}?force=true`

**This can lose the last several minutes of play.** It exists because the
alternative — a server that cannot be retired at all — is worse.

`superuser` only. It is for the state note 1 of `docs/operating.md` describes: a
persistent server whose world save cannot be confirmed, whose drain therefore
aborts, and which otherwise has to be stopped by hand.

What it does, in order: tombstone the definition, **request a world save and wait
the declared save timeout**, then stop the container with its **full declared
grace period** regardless of whether the save was confirmed. The reconcile loop
then observes the stopped container and completes the teardown as it always does.

What it does **not** do is skip the save or shorten the grace period. Skipping the
save would buy tens of seconds in exchange for the data this system exists to
protect; the grace period is the last protection still working when RCON is not.

**When no save request could be sent at all**, the grace period is *raised* to at
least the save timeout's default — on that branch the grace period stops being a
last-resort net and becomes the entire save, so it is given what this orchestrator
considers a save to be worth. It is never lowered, and a longer declared grace
period is left alone. A server that finishes early still exits early: the grace
period is a ceiling on how long containerd waits, not a delay.

```json
{ "accepted": true, "forced": true,
  "saveAttempted": false, "saveConfirmed": false, "saveOutstandingSince": null,
  "playersOnline": 12,
  "detail": "no world save could be sent — the container has no channel that could confirm one — so the stop grace period was the only chance the world had to reach disk" }
```

**Read `saveAttempted`, `saveConfirmed` and `saveOutstandingSince` together.**
They are different questions: a save can be *sent and never confirmed*, or **never
sent at all** — which is what happens on the very population this endpoint exists
for, a container with no working save channel. Collapsing them into "not
confirmed" would report those two identically, and they are not the same event.

**`saveOutstandingSince` is the one that stops `saveAttempted: false` misleading
you.** When it is non-null, the *drain* already had a save outstanding and this
stop deliberately sent none rather than putting a second `save-all flush` on a main
thread already running one. So a request demonstrably did go out — at that
instant — and the world may well be on disk. Without this field, that case is
indistinguishable from "no save channel existed", and the two point at opposite
conclusions. How long ago it was is what decides whether it plausibly landed, which
is why it is a timestamp rather than a flag.

**`playersOnline` may be `null`, and null is not zero.** It means the server did
not answer a count. Render it as unknown; a client that shows it as an empty
server is stating something this API did not.

**`playersOnline` is read immediately before the stop**, not when the request
arrived. The count is taken twice — once to decide, and again after the save wait,
because that wait can last a whole save timeout and nothing on this path holds a
player out of the server in the meantime. It is the second reading that is
reported and the second reading that can refuse.

### `?acknowledgeOccupancy=` takes a number, not `true`

- `?acknowledgeOccupancy=12` — *"I was shown 12 players and still want this."*
  Refused with `FORCE_REFUSED` if the count is anything but 12 when the stop is
  about to happen, so the acknowledgement cannot be stale.
- `?acknowledgeOccupancy=unreadable` — *"I was shown that the server does not
  answer a count and still want this."* It does **not** cover a server that
  answered.
- `?acknowledgeOccupancy=true` is `400 BAD_REQUEST`. It was the old spelling, and
  it is refused rather than reinterpreted: a caller sending it has not been shown
  a number.

A server observed with **zero** players needs no acknowledgement at all.

**A forced stop reaches the event stream on the status-poll cadence, not the
change feed.** Status writes never append to the feed, so the drain transition a
forced stop records is not pushed the moment it happens. It is still delivered
without waiting for anything else to write: the stream re-reads every server on
`statusPollInterval` and emits any status whose version has moved. So `GET` sees it
immediately and a stream consumer sees it within one poll interval — **bounded and
not dependent on the reconcile loop's next pass.**

Why a count: a boolean says *"proceed regardless"*, which cannot notice that the
population changed between an operator deciding and the request landing, and does
not require them to have looked. It would also be **mandatory on essentially every
legitimate use** — a wedged server does not answer a ping, so its occupancy is
always unreadable — which turns it into a fixed string in every runbook and
carries no information at the one moment it matters.

### It refuses rather than surprising you

Every refusal below is decided **before the definition is tombstoned**, and that
is a guarantee rather than an implementation detail: a tombstoned definition
cannot be edited, so a refusal saying *"correct that field and force again"* would
leave the server undrainable, unforceable, and reachable only with `crictl`.

- **A populated server** — or one whose occupancy could not be read — is `409
  FORCE_REFUSED` unless the acknowledgement above matches. Forcing disconnects
  those players without transferring them, and the drain would have moved them.
- **An unusable `spec.lifecycle.drain.saveTimeout`** is `FORCE_REFUSED`: no save
  could be sent, and the field is one edit away from making one possible.
- **A stop already in flight** is `FORCE_NOT_APPLICABLE`, while the container is
  still inside its grace period running its shutdown save; a second force would
  send another save into it. Once that window has passed the refusal lifts, so a
  stop the runtime refused does not lock the endpoint.
- An **unconfirmed save already outstanding** is *not* a refusal. The force skips
  its own save instead and reports `saveAttempted: false` — see above. Refusing
  would turn away exactly the wedged servers this endpoint exists for, because
  nothing clears that record on a server that answers no probe.
- **A `VelocityProxy`** is `FORCE_NOT_APPLICABLE` and **is not deleted** — it holds
  no world, so its drain cannot stall on a save.

If there is no running container to stop, this is not an error: the tombstone
stands, the response is the ordinary `202` with `"forced": false`, and the loop
completes the teardown. A repeated force answers the same way a repeated `DELETE`
does.

### What it still does not do

It does **not** attempt a player transfer. The drain does; this path does not, so
players are disconnected. That is the one dropped step the response states to your
face rather than leaving you to discover.

It **does** seal the login path at the proxy first, so nobody new is routed to a
server that is about to stop — which is also what makes `acknowledgeOccupancy` a
real check rather than a snapshot of a number that has already moved. A server
that is not behind a proxy has no door to shut, and there the count is re-read
immediately before the stop instead: narrower, not closed.

Deregistration is left to the reconcile loop's next pass. Until then the proxy
holds a registration at an address that is going away, which is harmless because
nothing new is being routed to it.

### `POST /api/v1/validate`

Validates a document and writes nothing. For a live editor: the same parser that
would reject the document on submit, so the two cannot disagree.

- `200 { "valid": true, "definition": { … } }` — the effective definition, every
  default resolved. Showing an operator what their omissions became is most of
  what this is for.
- `422` with violations.
- Authenticated like everything else: the violation text describes this
  deployment's rules.

---

## 7. `display` — the derived badge

One derivation, served by the server, so every dashboard does not invent its own.

```json
{ "state": "READY", "ready": true, "needsAttention": false, "unreadable": false,
  "drainBlocked": false, "drainState": null, "playersOnline": 3, "playersMax": 60,
  "proxy": null,
  "detail": "" }
```

`proxy` is null for a `PaperServer`, and populated for a `VelocityProxy` **once
there is an observation** — it is null, not zero-filled, while the proxy is
`neverObserved`. Read a null as "no counts yet" rather than "not a proxy"; the
`kind` discriminant is what tells you which kind you have:

```json
"proxy": { "backendsMatched": 5, "backendsRegistered": 2, "backendsDestinations": 1,
           "backendsObserved": true, "controlReachable": true, "controlCompatible": true,
           "controlUsable": true }
```

It exists so a fleet table can render a proxy row without reaching into
`status.backends` per row and re-deriving the counts — which is the sort of
derivation that ends up wrong in one place. The counts come from `:schema`'s own
derived properties, so a dashboard and the reconciler cannot disagree about what
"registered" means: `registered` counts `REGISTERED` **and** `SEALED` (both are in
the routing table), while `destinations` counts only those that may receive a
transfer right now.

`state` is computed top-down and the order is the whole definition:

1. `metadata.terminating` → **`TERMINATING`** (outranks everything: a server
   showing `READY` while its name is being reclaimed is the one wrong answer that
   matters)
2. `unreadable != null` → **`UNREADABLE`**
3. `neverObserved` → **`PENDING`**
4. a drain is in flight (`status.drain != null && state != DRAIN_FAILED`) →
   **`DRAINING`**
5. otherwise by `status.phase`:
   `FAILED`→`FAILED`, `UNKNOWN`→`UNKNOWN`, `PENDING`→`PENDING`,
   `IMAGE_PULLING`/`CREATING`/`STARTING`→`STARTING`,
   `DRAINING`→`DRAINING`, `STOPPING`→`STOPPING`, `STOPPED`→`STOPPED`,
   and `RUNNING`→ one of three:
   - `RUNNING` if `!ready` — up, not accepting yet
   - `DEGRADED` if a **capability condition is explicitly `False`**
   - `READY` otherwise

`RUNNING` vs `READY` is a real distinction: running is not joinable.

**`DEGRADED` — up, accepting, and unable to do its job.** A proxy whose selector
matches no backend is accepting players and routing them nowhere; one whose
control endpoint will not answer, or answers with an incompatible plugin, cannot
seal, transfer or deregister, so *no backend behind it can finish a drain*. Both
are up and both are broken, and neither is a failure the loop can act on — an
operator has to label a server or fix an image. `READY` would put a green badge
on a front door with nothing behind it.

The capability conditions today are `BACKENDS_RESOLVED` and
`CONTROL_ENDPOINT_READY`. The badge is deliberately general rather than a
proxy-specific `NO_BACKENDS`: it says *up and not working*, and which capability
is missing is in `display.detail` and in `status.conditions`. A kind that later
grows its own capability condition needs no new badge value.

Only an **explicitly `False`** condition degrades — never an absent one — so a
`PaperServer`, which raises neither, is never `DEGRADED` by omission.

`display.ready` stays the kind's own readiness and is **not** widened to mean
"and it has somewhere to send them". A `DEGRADED` proxy reports `ready: true`,
because it genuinely is accepting connections. The badge carries the rest, and
folding it into `ready` would make the field disagree with the `READY` condition
and with what `:core` wrote.

**`UNREADABLE` is not `UNKNOWN`.** `UNKNOWN` means the node or runtime could not
be reached — a fact about the world, and the remedy is to go and look at the
host. `UNREADABLE` means the stored observation will not decode — a fact about
our own record, where the container is very probably running exactly as it was
and the remedy is to repair a row. An operator sent to the wrong one of those
wastes an outage. It ranks below `TERMINATING` because a requested delete is a
*readable* fact about desired state and the more actionable badge; the flags
below carry the rest.

It was previously rendered as `PENDING`, which was wrong in the direction that
matters: `PENDING` is a state you wait out, and a corrupt row waited out for ever.

> **Recorded divergence.** The tenth drain audit specified reusing `UNKNOWN`
> here, to avoid adding an enum value and forcing a frontend release. The second
> half of that cost does not exist: `meta.enums.displayState` is served precisely
> so a new badge reaches a dashboard's filter chips with no code change, and both
> halves of that — the value is advertised, and `?state=UNREADABLE` selects on
> it — are pinned by a test. What remains is one enum value against conflating a
> broken *record* with an unreachable *host*, which are different problems with
> different remedies.

`needsAttention`, `unreadable` and `drainBlocked` are **flags, not states** — and
the flags are what you filter on, because `TERMINATING` outranks all three.

- `needsAttention` — **somebody must act.** True when a `NEEDS_ATTENTION`
  condition is `TRUE`, *and* whenever `unreadable` is set. It reports and never
  authorises, so a drain failing for an hour is still `DRAINING` with the flag
  beside it, and `lastTransitionAt` on the condition is what an alert fires on.

  **It is not a drain flag.** It escalates on two independent arms: a drain that
  cannot finish, and a *pass* the loop could not complete — the latter with no
  drain involved at all. The case that forced the second arm is a server carrying
  a permanent failure with `phase: "RUNNING"` and no drain, which the loop has
  stopped managing entirely and which otherwise sat in a fleet table looking
  perfectly healthy. Both arms apply the same threshold: `PERMANENT` escalates
  immediately, `RETRYABLE` only after a configured interval, so a transient blip
  does not page anybody.
- `unreadable` — **what is wrong.** True whenever the resource carries an
  `unreadable` mark, including when the badge says `TERMINATING`.
- `drainBlocked` — **the drain is waiting on players.** True when a
  `DRAIN_BLOCKED` condition is `TRUE`: the drain has stopped advancing and the
  *drain* has not failed. Today that means players are still connected and there
  is no proxy to move them through, so the protocol waits rather than
  disconnecting anybody. The container keeps running and the server stays
  joinable. Use `lastTransitionAt` on the condition for "blocked since when", or
  `status.drain.blocked.since` for the same instant on the record itself.

  **It is not on its own permission to ignore the server.** The condition asks
  only about the drain; it says nothing about whether the reconcile loop is
  still running. A pass that fails — a node that stops answering, say — leaves
  the block untouched and records on `status.failure`, so `drainBlocked` stays
  `true` while the drain is in fact not resuming at all. Read `status.failure`
  alongside it. `display.detail` already does, and words the sentence
  accordingly.

**Both `needsAttention` and `unreadable` are set for an unreadable row, and they
are not redundant.** A row the store cannot decode reads the same on every pass,
so the loop cannot move it and only a person repairing it can — which is
precisely what `needsAttention` is chartered to mean. Alert on `needsAttention`;
filter and label with `unreadable`. A dashboard that only watched
`needsAttention` would otherwise never see these servers, and that is the one
audience that has to.

**`drainBlocked` and `needsAttention` can both be true, and a client must order
them rather than treat them as exclusive.** An earlier version of this document
claimed they never were, and told dashboards to render them as a tri-state on
that basis. That was wrong, and wrong for exactly the case that most needs
attention: a drain can be *correctly* waiting on players while its node is
unreachable. The block is accurate — people really are still connected — and the
pass failure escalates independently of it.

Why the two are independent: `drainBlocked` is a fact about the *drain*, and
`needsAttention` is no longer a drain flag at all. It escalates on
`status.failure` too, so a server the loop has stopped acting on raises it whether
or not a drain is involved. The two arms can be true at once and neither implies
the other.

A drain that is not advancing shows `drainState: "DRAIN_FAILED"` whether it is
stuck or merely waiting — that state means *parked*, not *broken* — so the badge
alone cannot answer the only question an operator has about such a server. Render
the flags in priority order:

```ts
// Ordered, not exclusive: both can be true at once, and then the first wins
// because it is the one with an action attached.
const drain =
  display.needsAttention ? 'needs a human'
  : display.drainBlocked ? 'waiting for players'
  : 'in progress';
```

**Do not add a `status.failure` arm to that chain.** A previous revision of this
document did, and it was a mistake worth naming: it made the dashboard derive
"the loop has stopped moving this server" a fourth time, in TypeScript, with no
threshold at all — so every transient blip rendered as a problem. That fact
belongs in the condition, which applies the threshold (`PERMANENT` escalates
immediately, `RETRYABLE` only after a configured interval), and `needsAttention`
is how it reaches you. If you find yourself reading `status.failure` to decide
what to *render*, the answer is already in a flag.

There is one window this leaves, and it is deliberate: a *retryable* pass failure
below the threshold shows `needsAttention: false` while `drainBlocked` is true, so
the chip says "waiting for players". That is the threshold doing its job — a node
that blips for one pass is not something to call anybody about. `display.detail`
says so in prose immediately, which is the right strength of signal for a
transient fault: a sentence, not an alarm.

Do not infer the waiting case from `playersOnline > 0`. That was the only
discriminator available before this flag, and it is a coincidence of today's one
block reason rather than the fact itself — a *stuck* drain usually has players on
it too. `status.drain.blocked.reason` is the enumerated answer, advertised in
`meta.enums.drainBlockReason`.

#### `detail` — a failure always outranks a reassurance

`display.detail` is the one sentence, and its precedence is fixed:

1. the observation could not be read
2. `status.failure`, **when it is a different value from `status.drain.failure`**
   — the latest pass did not complete
3. `status.drain.failure` — the drain itself aborted
4. `drainBlocked` — waiting on players, and genuinely nothing to do
5. draining / terminating / caught-up wording

"Waiting, not stuck" tells somebody *not* to act, and it is only true while the
loop is running — so no failure can ever be outranked by it. The qualifier on (2)
is what keeps an aborted drain, which records the same failure in both fields,
reading as "the drain aborted" rather than losing that framing; when the two
values differ, something newer has gone wrong and it wins.

This precedence is derived from the same `DRAIN_BLOCKED` condition that
`drainBlocked` reports, so the flag and the sentence cannot disagree. They used
to: the flag read the condition and the sentence read `status.drain.blocked`
directly, so a record carrying both a block and a drain failure rendered
`drainBlocked: false` beside `detail: "waiting, not stuck"`.

##### `detail` and the `NEEDS_ATTENTION` message rank failures oppositely, on purpose

When a drain has aborted **and** a later, different pass failure is outstanding,
these two describe different things, and a client comparing them will otherwise
read it as a bug:

| | answers | picks |
|---|---|---|
| `NEEDS_ATTENTION` condition message | *what is the worst thing outstanding?* | the **drain** failure |
| `display.detail` | *what is true right now?* | the **pass** failure |

Both are right for their question. An alert wants the worst standing problem,
because that is what determines how much trouble the server is in; the sentence
under a row wants the most recent fact, because that is what an operator is
looking at the row to find out. A drain that aborted an hour ago is still the
bigger problem; a node that stopped answering two minutes ago is why nothing has
moved since.

So: render `detail` as the row's sentence, and the condition's message where you
explain the alert. Do not assert they match — they are not meant to.

A blocked drain records **no failure**: `status.failure` and
`status.drain.failure` are both `null`, and `status.drain.blocked` is set
instead. It used to record a `FailureReason`, which meant a server with people
happily playing on it lit up every "is anything wrong" panel a dashboard had.

`playersMax` falls back to `spec.maxPlayers` when nothing has been observed.

---

## 8. Live updates — `GET /api/v1/stream`

Server-sent events. Authenticate by **cookie** (`EventSource` cannot set
headers) or by bearer for a non-browser client.

### Why SSE and not a WebSocket

The dashboard needs server-to-client push and nothing else; every operator action
is a request with a status code and a body, which a WebSocket makes worse. SSE is
plain HTTP/1.1, so it inherits the cookie, the CORS decision and the reverse-proxy
config already in place. The one thing a WebSocket would buy — sending a header
on connect — is exactly what SSE cannot do either, which is why the session
cookie exists.

### `EventSource` or `fetch`? Both work. Read this before choosing.

Every frame this stream sends is a **named event**; there are no comment frames
and nothing is carried out of band. That is a deliberate constraint so the two
transports see exactly the same protocol:

- **`EventSource`** — reconnects and replays `Last-Event-ID` with no client code,
  honours `retry:` on its own. Least code.
- **`fetch` + `ReadableStream`** — you parse the framing yourself and own the
  reconnect policy. More code, full control.

Neither is blind to anything the other sees. This used to be false: the
keep-alive was an SSE *comment* (`: keep-alive`), and **`EventSource` does not
expose comment frames to script**. On an idle fleet that comment is the only
traffic between the opening snapshot and the lifetime cycle half an hour later,
so a half-open socket — a NAT timeout, a sleeping laptop, a middlebox dropping
the connection silently — left an `EventSource` client rendering half-hour-old
state with `readyState === OPEN` and no way to notice. It is now a `ping` event
(below), which both transports can see.

**Run a staleness watchdog either way.** `readyState === OPEN` is not evidence
that the connection is alive; a `ping` within the last few `keepAliveMillis` is.

```js
let lastBeat = Date.now();
const beat = () => { lastBeat = Date.now(); };
es.addEventListener('ping', beat);
es.addEventListener('snapshot', beat);
es.addEventListener('updated', beat);
// ~2.5 keep-alive intervals. Below 2 you will reconnect on ordinary jitter.
const stale = () => Date.now() - lastBeat > keepAliveMillis * 2.5;
```

### Opening

```js
const es = new EventSource('/api/v1/stream', { withCredentials: true });
```

Optional `?cursor=<token>` resumes from a known position. With no cursor the
stream **opens with a full snapshot**, so a client needs no separate list call and
there is no window between listing and subscribing in which a change can be lost.
`?cursor=` wins over `Last-Event-ID`; `?cursor=` set to the empty string forces a
snapshot even on a browser reconnect.

Before `hello`, the stream sends the SSE `retry:` field set to
`reconnectMillis` (3000). **`EventSource` honours this silently** and it becomes
your reconnect delay. A client with its own backoff simply ignores it; the same
value is in `hello` and in `meta.stream.reconnectMillis` so you can see what you
are overriding without reading a packet capture.

### Events

Every event carries `id:` set to the current cursor, so a browser reconnect
resumes correctly whichever event arrived last. A client that handles `snapshot`,
`updated` and `removed` is already correct — plus `ping` if you want the
watchdog, which you do.

| event | data | do |
|---|---|---|
| `hello` | `{cursor, resumed, changePollMillis, statusPollMillis, keepAliveMillis, maxLifetimeMillis, reconnectMillis}` | note the cursor |
| `snapshot` | `{cursor, count, items:[resource], unreadableCount, unreadable:[row]}` | replace the whole set |
| `updated` | `{name, reason, server}` | replace by name |
| `removed` | `{name, reason}` | delete by name |
| `unreadable` | `{name, part, reason, retryable}` | mark by name — **do not delete** |
| `ping` | `{at, cursor}` | reset the staleness watchdog |
| `expired` | `{cursor, message}` | nothing — a `snapshot` follows immediately |
| `bye` | `{reason:"MAX_LIFETIME", cursor}` | nothing — reconnect |

`reason` on `updated` is `"definition"` or `"status"`, **derived from which
version actually moved** rather than from whichever cadence noticed. It is for a
human reading a network tab; a client replaces by name either way. There is no
third value — a `"resync"` variant was once declared here and never emitted,
which is dead weight that reads as a gap in your code.

`removed` means the drain finished and `:core` purged the name. A *delete
request* arrives as `updated` with `terminating: true`.

`unreadable` means the store holds this row and cannot decode its **definition**,
so there is no resource to send — the same rows the list endpoint puts in its
`unreadable` array. It is emphatically **not** `removed`: the server was
declared, its container may well be up with players on it, and treating it as a
deletion is the failure this event exists to prevent. The row keeps whatever the
client last knew about it, with an error badge on top. If it starts decoding
again, an ordinary `updated` follows with the full resource.

A row that is unreadable when you connect is in the snapshot's `unreadable`
array, not in an event. The event is for a row that stops decoding while you are
watching.

#### A nameless row suspends `removed` until it is repaired

While any `unreadable` row has `name: null`, **this stream stops emitting
`removed` at all.** Not for that row — for every row.

`removed` is derived from absence: a name this connection has sent that the
listing no longer carries has been purged. A record with no name cannot be
matched against anything, and it may *be* any server whose name column was
nulled. Nothing here can tell which, so every name already sent becomes
un-eliminable, and deriving absence anyway would report a deletion that never
happened on a server that may have players on it.

The cost is the other direction of wrongness, and it is the one to accept: a
genuinely purged server lingers in your table until the nameless row is repaired
or the connection cycles into a fresh `snapshot`, which re-states everything.
A stale row is a stale dashboard; a running server reported gone is an operator
who thinks a server is stopped when it has players on it.

So: if you are showing a nameless row, also tell the operator that removals are
paused. Everything else — `updated`, `unreadable`, `snapshot`, `ping` — carries
on as normal.

`ping` arrives every `keepAliveMillis` whether or not anything changed, and
carries the cursor, so a watchdog that gives up and reconnects resumes from the
right place instead of re-listing.

`expired` is a real case a long-lived tab will hit: the change log is bounded, and
a connection that slept through enough writes cannot be told what it missed. A
client that ignores the event still converges, because the snapshot that follows
re-states everything.

### Two cadences

The store's change feed carries **desired state only** — the reconcile loop is the
only writer of observed state, so feeding observations back through it would be a
self-loop. So the stream runs two timers:

- `changePollMillis` (500 ms) — pull the change feed. An operator's edit shows up
  in another tab in well under a second.
- `statusPollMillis` (2 s) — re-read everything and compare observed-state
  versions. Slower because a status moves on the loop's cadence, and it doubles
  as the repair path for anything the feed lost.

Both funnel through one emit that drops anything whose definition and status
versions are unchanged, so the two cannot produce a duplicate between them.

The re-read is the **tolerant** one, and the fast path degrades into it: a row
the single-row read cannot decode is left to the next resync rather than ending
the stream, so it is reported within one `statusPollMillis` instead of taking
the connection down. One bad row does not blank a fleet.

### Backpressure

**There is no queue.** The connection's loop pulls current state and writes it
synchronously, so a client that stops reading blocks its own loop at the socket
and the loop stops pulling. Memory held for a stalled client is one snapshot plus
a socket buffer, and it does not grow with time.

Coalescing comes free from that: a client thirty seconds behind does **not**
receive thirty seconds of history when it drains. The next poll reads what is
true *now* and sends one update per server. **Intermediate states are dropped, by
design** — a dashboard wants the current value, not the path taken to it. If you
need every transition, you are looking for an audit log, which this is not.

Two hard bounds on top: `maxStreams` concurrent connections
(`503 STREAM_LIMIT`, retryable, with `Retry-After`), and `maxLifetimeMillis` per
connection, after which `bye` is sent and the socket closed. The browser
reconnects with `Last-Event-ID` — which means the resume path is exercised in
normal operation rather than only after a failure.

---

## 9. Secrets

A definition names a secret; it never contains one. Something has to put the
material where the reference points, and this is it. **One-way, always.**

| | | |
|---|---|---|
| `GET /api/v1/secrets` | `200 {"items":[{"name","keys":[…]}]}` | coordinates only |
| `GET /api/v1/secrets/{name}` | `200 {"name","keys":[…]}` / `404` | coordinates only |
| `PUT /api/v1/secrets/{name}/{key}` | `201` new / `200` replaced | body is the raw material |
| `DELETE /api/v1/secrets/{name}/{key}` | `204` / `404` | |
| `DELETE /api/v1/secrets/{name}` | `200 {"name","removedKeys":n}` / `404` | |
| `GET /api/v1/secrets/{name}/{key}` | **`405 SECRET_NOT_READABLE`** | always |

`PUT` takes `Content-Type: text/plain` or `application/octet-stream` and the raw
body — **not JSON**, deliberately, so material never passes through a JSON escape
and is never bound into a parser's intermediate strings. The body is decoded
straight into a `char[]`, and the intermediates are wiped. A non-UTF-8 body is a
`400` rather than being silently repaired with U+FFFD, which would store something
other than what was sent.

`201`/`200` returns `{ name, key, replaced, length }` — the length, never the
value.

The `GET` of a single key is **routed** to a refusal rather than left to a generic
404, and the refusal is identical whether or not the secret exists. A 404 would
read as "wrong coordinates" and invite a client to try others; this says the
operation does not exist. There is no debug view, no export and no reveal flag.

---

## 9.4 The remote console

```http
POST /api/v1/servers/{name}/console
Content-Type: text/plain

list
```

**This is the one endpoint here that is not a write to desired state.** §1 tells
you a 2xx means the request was recorded; on this route a `200` means the command
**ran on the server**. There is no loop behind it and no status to watch after.

```json
{ "server": "survival-01", "command": "list", "tier": "operator",
  "executedAt": "2026-08-13T09:14:02Z",
  "output": "There are 3 of a max of 20 players online: Alice, Bob, Carol" }
```

`output` is whatever the server replied, **verbatim**. It routinely carries player
names, UUIDs and client addresses — that is the point of a general console, and it
is why §13 has an exception for this route. Treat it as untrusted text and render
it escaped: it contains whatever a player typed.

The body is `text/plain`, one command, no leading slash. A body with more than one
line is a `400`, so a refusal and an audit record always refer to exactly one
thing. It is a body rather than a query parameter because a command routinely
names a player and a query string is logged by every proxy in the world.

### Two gates, and the route tier is neither

Reaching the handler needs `member`. What runs is decided after that:

1. **Invariant refusals.** `stop`, `save-off` and anything that ends the process
   are refused **at every tier, including `superuser`** — `CONSOLE_COMMAND_REFUSED`,
   pointing at `DELETE /api/v1/servers/{name}`, which drains. Stopping a server is
   not a permission this API grants to anybody.
2. **The tier gate.** `superuser` may run anything the first gate permits;
   `operator` and `member` have explicit sets. The effective tier is the lesser of
   your own and the server's `spec.console.maxTier`, so a refusal can be the
   *server's* doing — `FORBIDDEN` names both, so you can tell which.

### `GET /api/v1/servers/{name}/console`

What this caller may run here. `unrestricted: true` means the effective tier is
bounded only by the invariant refusals and there is no finite list — render a
free-text prompt. Otherwise `commands` is the set, and a picker is the honest UI.

### There is no scrollback

RCON is request/reply. It returns nothing between commands, so this shows the
replies to commands you sent and nothing else — no join and leave messages, no
server log. Live server output is container logs, which §11 still lists as absent.

---

## 9.5 Identities

Managing operators. **Every route here is `superuser`.**

| | |
|---|---|
| `GET /api/v1/identities` | name, tier, enabled, `createdAt`. **Never a digest** |
| `POST /api/v1/identities/{name}` | creates. Body is the tier |
| `PUT /api/v1/identities/{name}` | sets the tier. Body is the tier |
| `PUT /api/v1/identities/{name}/enabled` | body is `true` or `false` |
| `POST /api/v1/identities/{name}/credential` | rotates. No body |
| `DELETE /api/v1/identities/{name}` | removes |

Bodies are `text/plain` and one word, for the reason §5 gives for definitions
being YAML: nothing here parses JSON. It also keeps each endpoint doing one thing
— setting a tier and disabling are different decisions with different blast
radii.

### The credential is shown exactly once

`POST /identities/{name}` (`201`) and `POST /identities/{name}/credential` (`200`)
return a generated credential:

```json
{ "name": "rin", "tier": "operator", "credential": "…",
  "warning": "this credential is not stored in recoverable form and cannot be shown again. If it is lost, rotate it" }
```

**Store it at that moment.** It is kept only as a digest; there is no endpoint
that returns it again, and the listing carries no digest either. A caller that
loses one rotates.

This is the one place this API returns secret material, and §13 records why it is
not a contradiction.

### Disabling and rotating end live sessions

Both revoke every session belonging to that identity and report
`sessionsRevoked`. Without that, disabling would mean *"cannot log in again"*
while an existing session kept working — which is not what an operator revoking a
leaked credential intends. Rotation is usually the response to a leak, so it
matters most there.

### The last superuser

Demoting, disabling or removing the only enabled `superuser` is refused with
`LAST_SUPERUSER`. Not because it cannot be undone — `MCORCH_API_TOKEN` is the way
back — but because that recovery needs shell access to the host. Create or enable
another first.

---

## 10. Meta and health

### `GET /healthz` — unauthenticated

`200 {"status":"ok"}`. The only unauthenticated route with a body. It touches no
state on purpose: a liveness probe that fails when the database is slow turns a
degraded API into a restarted one, and restarting the process does not repair a
database.

### `GET /api/v1/meta` — authenticated

Every closed set the API can return **or accept**, so a dashboard hard-codes
none — not in a filter and not in a create form:

```json
{ "apiVersions": ["mcorch.dev/v1alpha1"], "currentApiVersion": "mcorch.dev/v1alpha1",
  "kinds": ["PaperServer", "VelocityProxy"],
  "enums": {
    "phase": [...], "drainState": [...], "conditionType": [...], "conditionStatus": [...],
    "failureReason": [...], "failureClass": [...], "drainBlockReason": [...],
    "displayState": [...],
    "statePart": ["DESIRED", "OBSERVED"],
    "backendRegistration": ["PENDING", "REGISTERED", "SEALED", "DEREGISTERED", "UNREACHABLE"],
    "storageMode": ["persistent", "ephemeral"],
    "forwardingMode": ["modern"],
    "drainPolicy": ["waitForZeroPlayers"]
  },
  "limits": { "maxBodyBytes": 1048576, "maxStreams": 16 },
  "stream": { "path": "/api/v1/stream", "changePollMillis": 500, "statusPollMillis": 2000,
              "keepAliveMillis": 15000, "maxLifetimeMillis": 1800000, "reconnectMillis": 3000 } }
```

#### Two spellings, and the split is not cosmetic

- **`phase`, `drainState`, `conditionType`, `conditionStatus`, `failureReason`,
  `failureClass`, `drainBlockReason`, `displayState`, `statePart`,
  `backendRegistration`** appear in *observed state* and are spelled by their
  Kotlin name: `RUNNING`, `DRAIN_STALLED`, `OBSERVED`, `SEALED`.
- **`storageMode`, `drainPolicy`, `forwardingMode`** appear in a *definition* and
  are spelled by their YAML wire value: `persistent`, `waitForZeroPlayers`,
  `modern`. A form that offered `PERSISTENT` would build a document the parser
  rejects.

The key name tells you which: `…State`/`…Type`/`…Reason`/`…Class`/`…Registration`
are read back, `storageMode`/`drainPolicy`/`forwardingMode` are sent.

#### What "without a frontend release" actually covers

A value added to one of `:schema`'s enums — a new `FailureReason`, a new
`ConditionType`, a new `StorageMode` — appears here immediately, with no change to
`:api` and none to the dashboard.

Two things are **not** covered, and it is better to know than to assume:

- **`displayState` is `:api`'s own enum, not `:schema`'s.** It is still served
  here, so a new badge still reaches your filters with no frontend release — but
  the guarantee is provided by `:api`, not by `:schema`. It cannot move to
  `:schema`: `TERMINATING` is derived from the store's tombstone and `PENDING`
  from the *absence* of an observation, and a tombstone is not a concept
  `:schema` has (deliberately — `metadata` has no `generation` either, for the
  same reason).
- **A new `ServerPhase` does not silently become a new `displayState`.** The
  mapping in §7 is exhaustive with no fallback, so a phase added in `:schema`
  fails `:api`'s compile until somebody decides which badge it maps to. That is
  intended: the alternative is a new phase quietly rendering as `UNKNOWN` on
  every dashboard. It does mean a `:schema` phase addition ships with an `:api`
  change — but never with a frontend one.

---

## 11. What is deliberately absent

**Container logs.** Reading them means reaching the runtime through the `Node`
abstraction, which lives in `:core`, and the log root is a node-local detail.
Adding it means adding a `:core` edge to `:api` — a real decision with a real
justification, not something to slip in. Until then: read them on the host.

**Restart.** There is no `POST /servers/{name}/restart`, because a restart is a
drain plus a recreate and the only honest way to express it is declaratively — a
field in the spec the loop can converge on. That is a `:schema` change and belongs
in one change with its consumers. Today a `PUT` that changes the spec is a
restart, and it drains first.

**Scale a pool.** There is no pool kind in the schema. One server, one definition.

**Purge.** `:core` owns the guard that a name is freed only once the containers
are gone. An endpoint that could reach past it would leave a running container
with nothing describing it.

**Stop / kill / force.** See §1. `RouteTableTest` asserts no route matching those
words exists.

**Serving the dashboard** is no longer absent: set `MCORCH_API_STATIC_ROOT` and
the bundle is served from this origin, with unmatched paths falling back to
`index.html` so client-side routing works. `/api/…` and `/healthz` are never
shadowed by a file, so a mistyped endpoint stays a `404` rather than becoming a
page a client would parse as JSON.

Same origin is the intended deployment: a cross-site dashboard needs
`SameSite=None`, which needs `Secure`, which needs TLS. **A TLS-terminating proxy
in front reinstates the access logs that same-origin removes** — worth knowing
before adding one.

**Metrics and pagination.** Not needed at this scale. `GET /api/v1/servers`
returns everything; the change feed is the incremental path.

**An audit log.** Still absent. The console (`spec/`) is what will need one, and
it lands with the console rather than ahead of it.

Per-user roles are no longer absent — see §2.1 and §9.

---

## 12. Configuration

| variable | default | notes |
|---|---|---|
| `MCORCH_API_LISTEN` | `127.0.0.1:8080` | `off` disables the API entirely |
| `MCORCH_API_TOKEN` | **none** | required, ≥ 32 chars. `head -c 32 /dev/urandom \| base64` |
| `MCORCH_API_ALLOWED_ORIGINS` | empty | comma-separated exact origins |
| `MCORCH_API_SESSION_TTL` | `12h` | `30s`, `5m`, `1m30s` — not ISO-8601 |
| `MCORCH_API_MAX_STREAMS` | `16` | |
| `MCORCH_API_MAX_BODY_BYTES` | `1048576` | |
| `MCORCH_API_COOKIE_SECURE` | `false` on loopback, else `true` | |
| `MCORCH_API_COOKIE_SAMESITE` | `Strict` | `Lax`, `None` (needs `Secure`) |
| `MCORCH_API_STATIC_ROOT` | unset | the dashboard bundle to serve. Unset means API-only; a path that is not a directory fails at startup |

There is **no default token**. Starting an unauthenticated API because a variable
was unset is not something this can do: every mutating endpoint can request a
drain, and a drain is how a Minecraft server stops. A missing token exits 78; so
does a bind failure, because an orchestrator whose dashboard silently did not
start looks exactly like a healthy one until somebody needs it.

---

## 13. Data this API never returns

- **No player names, UUIDs or client addresses.** Not by filtering — there is
  nothing in the objects to leave out. `status.players` is
  `{online, max, observedAt}` and the type has no field an identity could live in.
  `status.endpoint.address` is the *server's* address, never a client's.
  `status.drain.destination` is a server name.
- **No secret material the operator supplied, ever.** `spec.network.rcon.passwordSecret`,
  `spec.forwarding.secret` and `spec.control.tokenSecret` are all `{name, key}` —
  coordinates. There is no endpoint that resolves any of them. The second is the
  modern-forwarding secret, which the repository's fourth invariant says travels
  through the secret store and nowhere else; it is set through
  `PUT /api/v1/secrets/{name}/{key}` and never read back.
- **A proxy sees every player in the fleet**, so the counts-only rule matters
  most there. `status.backends[].players` is `{online, max, observedAt}` and
  `BackendStatus` has no field an identity could live in. `backends[].server` is
  a declared object's name.
- **`status.failure.message` and `status.drain.failure.message`** are
  operator-facing and already redacted upstream for the CRI operations whose
  request carries a secret. There is no second, unredacted view and no raw-state
  endpoint.

- **`unreadable.reason`**, on a resource, in a listing and in a
  `SERVER_UNREADABLE` error, is the store's own operator-facing text. It names
  the server and what about the stored form was rejected, and it carries no stack
  trace, no class name, no SQL and no file path — `:store` does not put them in
  the value, and nothing here reaches past it to the exception to add them.

- **The console is the exception to all of the above.**
  `POST /api/v1/servers/{name}/console` returns the server's reply verbatim, which
  routinely contains player names, UUIDs and client addresses. It is the one route
  where the guarantee does not hold, it is deliberate, and `ResponseLeakageTest`
  exempts exactly it. A general console cannot be built otherwise: the alternative
  needs a parser per command and cannot support a modded server's commands at all.
- **One further exception, and it runs the other way.** `POST /api/v1/identities/{name}`
  and `POST /api/v1/identities/{name}/credential` return a credential this API
  *generated* — see §9.5. The rule above is about material an operator handed in,
  which is never returned; a generated credential shown once is the only way it
  can ever be used, since there is no other channel to deliver it. It is stored
  as a digest and is not readable again.

`ResponseLeakageTest` enforces all of this against every response body an
operator can obtain, with control assertions proving the search could have
failed; `StoreFailureTest` does the same for the unreadable paths, which no real
store will produce on demand.

---

## 14. TypeScript

```ts
export type ApiVersion = 'mcorch.dev/v1alpha1';
export type Kind = 'PaperServer' | 'VelocityProxy';

export type ServerPhase =
  | 'PENDING' | 'IMAGE_PULLING' | 'CREATING' | 'STARTING' | 'RUNNING'
  | 'DRAINING' | 'STOPPING' | 'STOPPED' | 'FAILED' | 'UNKNOWN';

export type DrainState =
  | 'DRAIN_REQUESTED' | 'SEALED' | 'TARGET_RESOLVED' | 'TRANSFERRING'
  | 'SAVING' | 'DEREGISTERED' | 'STOPPING' | 'DRAIN_FAILED';

export type DisplayState =
  | 'PENDING' | 'STARTING' | 'RUNNING' | 'READY' | 'DRAINING'
  | 'TERMINATING' | 'STOPPING' | 'STOPPED' | 'FAILED'
  /** Up, accepting connections, and unable to do its job — see §7. */
  | 'DEGRADED'
  /** The stored observation will not decode. NOT the same as UNKNOWN — see §7. */
  | 'UNREADABLE'
  | 'UNKNOWN';

/**
 * A transcription of `:schema`'s enum, which `/meta.enums.conditionType` serves
 * live from `ConditionType.entries`. When the two disagree, `/meta` is right and
 * this is stale — it has been stale before. Do not switch exhaustively on it
 * without a default arm.
 */
export type ConditionType =
  | 'IMAGE_AVAILABLE' | 'VOLUME_BOUND' | 'CONTAINER_RUNNING' | 'READY'
  | 'DRAINING'
  /** Parked and nothing is wrong. The inverse of NEEDS_ATTENTION — see §7. */
  | 'DRAIN_BLOCKED'
  | 'PLAYERS_EVACUATED' | 'WORLD_SAVED'
  /** VelocityProxy only. False is not a failure: the proxy runs, routing nowhere. */
  | 'BACKENDS_RESOLVED'
  /** VelocityProxy only. False means seal, transfer and deregister are unavailable. */
  | 'CONTROL_ENDPOINT_READY'
  | 'NEEDS_ATTENTION';

export type ConditionStatus = 'TRUE' | 'FALSE' | 'UNKNOWN';
export type FailureClass = 'RETRYABLE' | 'PERMANENT';

/**
 * Why a drain has stopped advancing when nothing has gone wrong. Not a
 * FailureReason, and deliberately not one: see DrainBlock below.
 */
export type DrainBlockReason = 'AWAITING_ZERO_PLAYERS';

/** Which half of a server's stored state something is about. */
export type StatePart = 'DESIRED' | 'OBSERVED';

/** How the proxy currently routes to one backend. The drain protocol's own vocabulary. */
export type BackendRegistration =
  | 'PENDING' | 'REGISTERED' | 'SEALED' | 'DEREGISTERED' | 'UNREACHABLE';

/** Wire value. The only forwarding this orchestrator will run. */
export type ForwardingMode = 'modern';

export type FailureReason =
  | 'IMAGE_PULL_FAILED' | 'IMAGE_REFERENCE_REJECTED' | 'SANDBOX_CREATE_FAILED'
  | 'CONTAINER_CREATE_FAILED' | 'CONTAINER_START_FAILED' | 'CONTAINER_EXITED'
  | 'READINESS_TIMEOUT' | 'VOLUME_UNAVAILABLE' | 'NODE_UNAVAILABLE'
  | 'RUNTIME_UNREACHABLE'
  /**
   * A destination was searched for and no server in the fleet had capacity.
   * NOT "waiting for players to log off" — that is DrainBlockReason
   * 'AWAITING_ZERO_PLAYERS' and is not a failure at all. This one needs an
   * operator to add capacity, and raises NEEDS_ATTENTION once it has been true
   * for long enough.
   */
  | 'DRAIN_NO_DESTINATION'
  | 'DRAIN_TRANSFER_FAILED'
  | 'DRAIN_SAVE_TIMEOUT' | 'DRAIN_STALLED'
  | 'PROXY_CONTROL_UNREACHABLE' | 'PROXY_PLUGIN_INCOMPATIBLE'
  | 'FORWARDING_SECRET_UNAVAILABLE'
  | 'UNKNOWN';

/** Wire values, because these are written back into a definition. */
export type StorageMode = 'persistent' | 'ephemeral';
export type DrainPolicy = 'waitForZeroPlayers';

// ── what you SEND ───────────────────────────────────────────────────────────

/**
 * The body of POST /servers, PUT /servers/{name} and POST /validate.
 *
 * Everything the parser defaults is optional here, which is most of the spec:
 * a four-field document validates. Note `?:` and NOT `| null` throughout — an
 * explicit `null` is a violation, not "use the default" (§6). `JSON.stringify`
 * drops `undefined` properties, so an optional property left unset is correct;
 * one set to `null` is a 422.
 *
 * Unknown fields are rejected with a violation naming the field, so this is not
 * merely advisory — a typo is a 422 with `did you mean …?` attached.
 */
export type DefinitionInput = PaperServerInput | VelocityProxyInput;

export interface PaperServerInput {
  apiVersion: ApiVersion;
  kind: 'PaperServer';
  metadata: { name: string; labels?: Record<string, string> };
  spec: PaperServerSpecInput;
}

export interface VelocityProxyInput {
  apiVersion: ApiVersion;
  kind: 'VelocityProxy';
  metadata: { name: string; labels?: Record<string, string> };
  spec: VelocityProxySpecInput;
}

export interface VelocityProxySpecInput {
  /** Required. Pinned to a tag or a digest; `latest` is rejected. */
  image: string;
  /** Required — but only `memory` inside it is. */
  resources: {
    memory: string;
    cpu?: string;
    heap?: { max?: string; min?: string };
  };
  /** Required. The coordinate of the modern-forwarding secret — never a value. */
  forwarding: { secret: SecretRef; mode?: ForwardingMode };
  /** Required. `matchLabels` must be non-empty: an empty selector enrols the fleet. */
  backends: {
    selector: { matchLabels: Record<string, string> };
    fallback?: string[];
    drain?: { sealTimeout?: string; destinationTimeout?: string; deregisterTimeout?: string };
  };
  /** `tokenSecret` becomes required once `hostPort` is set — checked at parse time. */
  control?: { port?: number; hostPort?: number; tokenSecret?: SecretRef };
  maxPlayers?: number;                  // default 500
  network?: { port?: number; hostPort?: number };
  lifecycle?: {
    /** No wait timeout, and there will not be one: the only way to spell it is "disconnect them". */
    drain?: { policy?: DrainPolicy; sealTimeout?: string };
    stopGracePeriod?: string;
    startupTimeout?: string;
  };
  placement?: { node?: string };
  /** There is no `storage` block and no way to ask for one. A proxy holds no world. */
}

export interface PaperServerSpecInput {
  /** Required. Pinned to a tag or a digest; `latest` is rejected. */
  image: string;
  /** Required. `build` is optional. */
  paper: { minecraftVersion: string; build?: number };
  /** Required, and must be `true`. A Paper server never starts without it. */
  eulaAccepted: true;
  /** Required — but only `memory` inside it is. */
  resources: {
    memory: string;                     // `4Gi`, `512Mi`, `2G`
    cpu?: string;                       // `2`, `1.5`, `500m`
    /** Defaults to the largest heap that leaves the container headroom. */
    heap?: { max?: string; min?: string };
  };
  maxPlayers?: number;                  // default 20
  network?: {
    port?: number;                      // default 25565
    hostPort?: number;
    /** Required. RCON is standard; `port` defaults to 25575. */
    rcon: { port?: number; passwordSecret: SecretRef };
  };
  /** Defaults to persistent, on a volume named after the server. */
  storage?:
    | { mode?: 'persistent'; mountPath?: string; volume?: { name?: string; size?: string } }
    /** `volume` must NOT be set here — a 422 if it is. */
    | { mode: 'ephemeral'; mountPath?: string };
  lifecycle?: {
    drain?: { policy?: DrainPolicy; playerTransferTimeout?: string; saveTimeout?: string };
    /** Must exceed `drain.saveTimeout` by at least 30s. Default: saveTimeout + 60s. */
    stopGracePeriod?: string;
    startupTimeout?: string;
  };
  placement?: { node?: string };        // omit and the scheduler chooses
}

// ── what you RECEIVE ────────────────────────────────────────────────────────

/**
 * The `definition` field of a server resource. Absent optional fields are
 * OMITTED, not null (§6) — which is precisely what makes it assignable to
 * `DefinitionInput`, so a fetched definition can be edited and PUT back with no
 * cast and no rebuild:
 *
 *   const draft: DefinitionInput = server.definition;   // compiles
 *
 * Unlike `DefinitionInput`, every defaulted field is present: this is the
 * *effective* definition the reconciler acts on, not what the operator typed.
 */
export type Definition = PaperServerDefinition | VelocityProxyDefinition;

export interface PaperServerDefinition {
  apiVersion: ApiVersion;
  kind: 'PaperServer';
  metadata: { name: string; labels?: Record<string, string> };
  spec: PaperServerSpec;
}

export interface VelocityProxyDefinition {
  apiVersion: ApiVersion;
  kind: 'VelocityProxy';
  metadata: { name: string; labels?: Record<string, string> };
  spec: VelocityProxySpec;
}

export interface VelocityProxySpec {
  image: string;
  maxPlayers: number;
  network: { port: number; hostPort?: number };
  resources: { memory: string; cpu?: string; heap: { max: string; min: string } };
  forwarding: { mode: ForwardingMode; secret: SecretRef };
  backends: {
    selector: { matchLabels: Record<string, string> };
    fallback?: string[];
    drain: { sealTimeout: string; destinationTimeout: string; deregisterTimeout: string };
  };
  control: { port: number; hostPort?: number; tokenSecret?: SecretRef };
  lifecycle: {
    drain: { policy: DrainPolicy; sealTimeout: string };
    stopGracePeriod: string;
    startupTimeout: string;
  };
  placement?: { node: string };
}

export interface PaperServerSpec {
  image: string;                                   // pinned: a tag or a digest, never `latest`
  paper: { minecraftVersion: string; build?: number };
  eulaAccepted: true;
  maxPlayers: number;
  network: {
    port: number;
    hostPort?: number;
    rcon: { port: number; passwordSecret: SecretRef };
  };
  resources: { memory: string; cpu?: string; heap: { max: string; min: string } };
  storage:
    | { mode: 'persistent'; mountPath: string; volume: { name: string; size?: string } }
    | { mode: 'ephemeral'; mountPath: string };
  lifecycle: {
    drain: { policy: 'waitForZeroPlayers'; playerTransferTimeout: string; saveTimeout: string };
    stopGracePeriod: string;                       // always > saveTimeout + 30s
    startupTimeout: string;
  };
  placement?: { node: string };
}

/** Coordinates. There is no endpoint that turns this into a value. */
export interface SecretRef { name: string; key: string }

export type ServerStatus = PaperServerStatus | VelocityProxyStatus;

/**
 * Observed state of a proxy.
 *
 * Not a `PaperServerStatus` with fields removed. There is no `storage` — a proxy
 * holds no world, and a nullable storage block would invite "not persistent yet"
 * from an absence. What it has instead is the two observations only a proxy can
 * make.
 */
export interface VelocityProxyStatus {
  apiVersion: ApiVersion; kind: 'VelocityProxy'; name: string;
  observedGeneration: number;
  phase: ServerPhase;
  observedAt: string; lastTransitionAt: string;
  /** Accepting player connections. Says nothing about having anywhere to send them. */
  ready: boolean; draining: boolean;
  image: ImageStatus | null;
  runtime: RuntimeIdentity | null;
  endpoint: { node: string; address: string; port: number } | null;
  players: { online: number; max: number; observedAt: string } | null;
  /** `null` = never observed. Present with `matched: 0` = the selector matched nothing. */
  backends: BackendRoutingStatus | null;
  control: ControlEndpointStatus | null;
  drain: DrainStatus | null;
  failure: FailureStatus | null;
  conditions: Array<{ type: ConditionType; status: ConditionStatus; message: string; lastTransitionAt: string }>;
}

export interface BackendRoutingStatus {
  observedAt: string;
  /** Matched by the selector, whatever state they are in. */
  matched: number;
  /** In the routing table: REGISTERED or SEALED. */
  registered: number;
  /** May receive a transfer right now: REGISTERED and not draining. */
  destinations: number;
  backends: BackendStatus[];
}

export interface BackendStatus {
  server: string;                       // a declared object's name
  registration: BackendRegistration;
  players: { online: number; max: number; observedAt: string } | null;
  drainInitiated: boolean;
  eligibleAsDestination: boolean;
  lastTransitionAt: string;
}

export interface ControlEndpointStatus {
  reachable: boolean;
  /** What the endpoint reported, never anything declared. */
  pluginApiVersion: string | null;
  compatible: boolean;
  lastContactAt: string | null;
  /** What an authenticated call did. 'UNTESTED' is *no evidence*, not a verdict. */
  credential: ControlCredential;
  /**
   * `reachable && compatible && credential !== 'REJECTED'`. The one derivation,
   * computed server-side. 'UNTESTED' counts as not-refused rather than
   * not-accepted, so this is false only where a call was actually observed to
   * fail.
   */
  usable: boolean;
}

/**
 * The handshake route (`GET /v1/version`) needs no token by design — that is what
 * lets a wrong credential be told from a wrong port — so `reachable` and
 * `compatible` say nothing about whether the orchestrator can drive the proxy.
 *
 * 'REJECTED' is reached by rotating the secret behind `spec.control.tokenSecret`:
 * the container keeps the token it was created with, nothing in the spec hash
 * moved, and every seal, transfer and deregistration is refused. The remedy needs
 * no definition edit, so the failure recorded beside it is retryable.
 */
export type ControlCredential = 'UNTESTED' | 'ACCEPTED' | 'REJECTED';

/** Absent optional fields are `null` here, not omitted. */
export interface PaperServerStatus {
  apiVersion: ApiVersion; kind: 'PaperServer'; name: string;
  observedGeneration: number;
  phase: ServerPhase;
  observedAt: string; lastTransitionAt: string;
  ready: boolean; draining: boolean;
  image: { requested: string; resolvedDigest: string | null; pulledAt: string | null; available: boolean } | null;
  runtime: {
    node: string; sandboxId: string; containerId: string | null;
    createdAt: string | null; startedAt: string | null; finishedAt: string | null;
    exitCode: number | null; restartCount: number;
  } | null;
  endpoint: { node: string; address: string; port: number } | null;   // the SERVER's address
  players: { online: number; max: number; observedAt: string } | null; // counts only
  storage: { persistent: boolean; volumeName: string | null; bound: boolean; lastSaveConfirmedAt: string | null } | null;
  drain: DrainStatus | null;
  failure: FailureStatus | null;
  conditions: Array<{ type: ConditionType; status: ConditionStatus; message: string; lastTransitionAt: string }>;
}

export interface DrainStatus {
  state: DrainState;
  startedAt: string; enteredStateAt: string;
  playersEvacuated: boolean;
  sealRequestedAt: string | null;
  saveRequestedAt: string | null;   // a save request that went out and was NOT confirmed
  worldSavedAt: string | null;      // a COMPLETED save. Disjoint from saveRequestedAt.
  worldSaved: boolean;
  /**
   * When this drain first had to save the world *again* because the confirmation
   * it held had stopped describing the running container. Null on a drain that
   * has never lost one. Set once, and reset only by a probe that saw somebody on
   * the server; beside a `DRAIN_STALLED` failure it means the drain is going
   * round the save rather than stuck on a single attempt.
   */
  resaveForcedAt: string | null;
  deregisteredAt: string | null;
  /**
   * When a container stop request for this drain left the orchestrator. Set once
   * and never cleared. Read it beside `deregisteredAt`: a parked drain with both
   * set is a backend deliberately kept out of the proxy's routing table, because
   * the container has been sent SIGTERM and re-admitting players to a process in
   * shutdown loses their session. Non-null does NOT mean the container stopped.
   */
  stopDispatchedAt: string | null;
  transferStartedAt: string | null; // the anchor step 4's allowance is measured from
  transferAttempts: number;
  destination: string | null;       // a server name, never a player
  /** Parked and healthy. Disjoint from `failure` — see below. */
  blocked: DrainBlock | null;
  failure: FailureStatus | null;
}

export interface FailureStatus {
  reason: FailureReason; failureClass: FailureClass;
  message: string;                  // redacted upstream; no unredacted view exists
  occurredAt: string; attempts: number;
}

/**
 * A drain that is waiting rather than broken.
 *
 * The same shape as `FailureStatus` minus `failureClass`, and the missing field
 * is the point: a block is always retried, so there is nothing to classify. It
 * is a sibling of `failure` rather than a variant of it, and the two are
 * disjoint — read `blocked !== null && failure === null` as *waiting*:
 *
 *   state             drain.blocked   drain.failure
 *   progressing       null            null            (and state !== 'DRAIN_FAILED')
 *   blocked, healthy  set             null
 *   failed            null            set
 *
 * `since` is when the block was first recorded, not when the loop last looked;
 * `observations` is how many passes have found it still true, which is what says
 * the loop is still watching rather than wedged. Count elapsed time from `since`
 * against your own clock — the server does not render a duration, because one
 * would be stale the moment it was written.
 */
export interface DrainBlock {
  reason: DrainBlockReason;
  message: string;                  // counts and prose; never a player identity
  since: string;
  observations: number;
}

export interface ServerResource {
  name: string; kind: Kind; apiVersion: ApiVersion;
  definition: Definition;
  metadata: {
    generation: number; resourceVersion: string;
    createdAt: string; updatedAt: string;
    deletedAt: string | null; terminating: boolean;
  };
  status: ServerStatus | null;
  statusMeta: { resourceVersion: string; recordedAt: string } | null;
  /** Why `status` is null, when the answer is not "nothing has been observed". */
  unreadable: Unreadable | null;
  caughtUp: boolean;
  /** `status === null && unreadable === null`. Test this, not `status === null`. */
  neverObserved: boolean;
  display: {
    state: DisplayState; ready: boolean; needsAttention: boolean;
    /**
     * True whenever `unreadable` is set, including when the badge says
     * TERMINATING. `needsAttention` is also true in that case — see §7: this one
     * says what is wrong, that one says somebody must act.
     */
    unreadable: boolean;
    /**
     * The drain is parked and nothing is wrong — **do not act**. Never true at
     * the same time as `needsAttention`; the two together are the tri-state in
     * §7. Do not infer this from `playersOnline > 0`.
     */
    drainBlocked: boolean;
    /** 'DRAIN_FAILED' means *parked*, not *broken*. Read it with `drainBlocked`. */
    drainState: DrainState | null;
    playersOnline: number | null; playersMax: number | null;
    /** Kind-specific headline numbers. Null for a kind that has none. */
    proxy: ProxyFacts | null;
    detail: string;
  };
}

export interface ProxyFacts {
  /** All null until something has looked — see `backendsObserved`. */
  backendsMatched: number | null;
  backendsRegistered: number | null;
  backendsDestinations: number | null;
  /** False = never observed. True with `backendsMatched: 0` = the selector matched nothing. */
  backendsObserved: boolean;
  controlReachable: boolean | null;
  controlCompatible: boolean | null;
  /**
   * The badge to render. `controlReachable && controlCompatible` is **not** it: a
   * proxy that answers, speaks our protocol and refuses our control token is true
   * on both while no backend behind it can be sealed, transferred or
   * deregistered. Server-derived, so it cannot drift from the condition.
   */
  controlUsable: boolean | null;
}

/**
 * A part of a server's stored state the store holds and cannot decode.
 *
 * `reason` is operator-facing text on the same terms as `FailureStatus.message`:
 * safe to show, carrying no stack trace and no storage-level detail. `retryable`
 * is false for anything that failed to decode.
 */
export interface Unreadable { part: StatePart; reason: string; retryable: boolean }

/**
 * A row the store has a name for and nothing else — its *definition* will not
 * decode, so there is no resource. Reported rather than omitted because absence
 * is how a purge is reported. `name` is the raw stored string: the name itself
 * can be why the row will not read.
 */
export interface UnreadableServer {
  /** Null when the record has no name at all — see §6. Not a placeholder. */
  name: string | null;
  part: StatePart; reason: string; retryable: boolean;
}

export interface ServerList {
  cursor: string;
  count: number;
  items: ServerResource[];
  unreadableCount: number;
  /** Never filtered — see §6. */
  unreadable: UnreadableServer[];
}

export type ErrorCode =
  | 'BAD_REQUEST' | 'UNAUTHENTICATED' | 'CSRF_REQUIRED' | 'CSRF_INVALID'
  | 'ORIGIN_NOT_ALLOWED' | 'NOT_FOUND' | 'METHOD_NOT_ALLOWED' | 'SECRET_NOT_READABLE'
  | 'CONFLICT' | 'PAYLOAD_TOO_LARGE' | 'UNSUPPORTED_MEDIA_TYPE' | 'VALIDATION_FAILED'
  | 'PRECONDITION_REQUIRED' | 'INTERNAL' | 'SERVER_UNREADABLE'
  | 'STORE_UNAVAILABLE' | 'STREAM_LIMIT';

export interface ApiError {
  error: {
    code: ErrorCode;
    message: string;
    retryable: boolean;
    violations: Array<{
      field: string; problem: string;
      location: { source: string; line: number; column: number } | null;
    }> | null;
    conflict: {
      name: string;
      reason: 'ALREADY_EXISTS' | 'VERSION_MISMATCH' | 'NOT_FOUND' | 'TERMINATING'
            | 'NOT_DELETED' | 'KIND_MISMATCH' | 'DEFINITION_CHANGED';
      currentResourceVersion: string | null;
      explanation: string;
    } | null;
    /** Set on SERVER_UNREADABLE. Not evidence about the container — see §3. */
    unreadable: (Unreadable & { name: string | null }) | null;
  };
}

export type StreamEvent =
  | { type: 'hello';    data: { cursor: string; resumed: boolean; changePollMillis: number;
                                statusPollMillis: number; keepAliveMillis: number;
                                maxLifetimeMillis: number; reconnectMillis: number } }
  | { type: 'snapshot'; data: { cursor: string; count: number; items: ServerResource[];
                                unreadableCount: number; unreadable: UnreadableServer[] } }
  | { type: 'updated';  data: { name: string; reason: 'definition' | 'status'; server: ServerResource } }
  | { type: 'removed';  data: { name: string; reason: 'PURGED' } }
  | { type: 'unreadable'; data: UnreadableServer }
  | { type: 'ping';     data: { at: string; cursor: string } }
  | { type: 'expired';  data: { cursor: string; message: string } }
  | { type: 'bye';      data: { reason: 'MAX_LIFETIME'; cursor: string } };
```

### The three flows worth writing down

**Bootstrap.** `POST /auth/session` with the token → keep `csrfToken` in memory →
`GET /meta` once for the enumerations → open the stream with `withCredentials` →
build the table from `snapshot`, apply `updated`/`removed`, reset a staleness
watchdog on `ping`. No list call needed.

**Edit.** `GET /servers/{name}` → assign `server.definition` to a
`DefinitionInput` (it fits) → edit → `PUT` with `If-Match: <ETag>` and
`X-CSRF-Token`. On `409`, re-read, re-apply, retry. On `422`, attach each
violation to its `field`.

**Delete.** `DELETE /servers/{name}` → `202` → keep the row, render
`TERMINATING` and `status.drain.state` → the row goes when `removed` arrives or
`GET` answers `404`. If `drain.state` becomes `DRAIN_FAILED`, say so loudly: the
server is still running and needs an operator.

**A row the store cannot read.** Two shapes, and neither is a disappearance:

- `unreadable` set on a resource → the *observation* is corrupt. The row is
  otherwise normal: definition, labels, spec all readable. Badge `UNREADABLE`,
  show `unreadable.reason`, and say that nothing under `status` reflects what the
  server is doing. `display.needsAttention` is set too, so it appears in whatever
  the operator alerts on. The reconcile loop will write a fresh observation over
  the broken one on its own.
- an entry in `unreadable[]` (list or snapshot), or an `unreadable` event → the
  *definition* is corrupt. There is no resource. Keep whatever you last knew
  about the name, badge it, and offer the repair: `PUT` a valid definition with
  `If-Match: *`.
- the same, with `name: null` → the record has no name either. Render it, key it
  by index rather than by name, and offer **no** action: every repair path names
  a server, so this one is fixable only in the store. While one exists, `removed`
  events are suspended — say so.

In both cases the container is very probably running exactly as it was. Do not
render either as an outage, and never as a deletion.

**A proxy row.** Branch on `kind` for the detail panel, but the fleet table needs
no branch: `display` is common, and `display.proxy` carries the numbers a proxy
row wants. `DEGRADED` is the badge to give a distinct colour — it is the one that
means "up, and nobody can play" — and `display.detail` already says which
capability is missing. `status.backends === null` is "nothing has looked yet";
`status.backends.matched === 0` is "the selector matched nothing", which is the
answer to *why can nobody join* and wants an operator, not a spinner.
