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

Single host, one class of user, no tenancy. In priority order this defends
against:

1. **Anything else that can reach the port.** Every route except `/healthz` and
   the CORS preflight requires a credential. The default bind is `127.0.0.1`.
2. **A hostile page in the operator's browser.** Three independent controls:
   `SameSite` on the cookie, an origin allow-list checked before any handler
   runs, and a double-submit CSRF token on every cookie-authenticated mutation.
3. **Script running in the dashboard's own page.** The session cookie is
   `HttpOnly`, so injected script cannot read or exfiltrate it. The CSRF token is
   readable on purpose — it is not a credential on its own.
4. **Guessing the operator token.** Minimum 32 characters, compared as a SHA-256
   digest in constant time, fixed delay on every failure. No lockout: a lockout on
   a shared credential is a denial of service anyone who can reach the port can
   trigger.

Explicitly **not** defended against: an attacker who can read the host's
environment or process table (the token is an env var), transport interception
(this server speaks plain HTTP), and a hostile operator. **There are no roles** —
any authenticated caller can do anything the API offers.

### Two credentials

| | Header | CSRF needed | For |
|---|---|---|---|
| Operator token | `Authorization: Bearer <token>` | no | scripts, `curl`, CI |
| Session cookie | `Cookie: mcorch_session=…` | yes, on mutations | the SPA |

The bearer exemption is not a convenience: a browser never attaches an
`Authorization` header on its own, so a cross-site page cannot produce that
request at all and a CSRF token would add nothing.

The cookie exists because `EventSource` cannot set headers — the live stream
(§8) can only authenticate by cookie. Given that, the SPA should use the cookie
everywhere: an `HttpOnly` cookie is a credential injected script cannot lift,
whereas an operator token in `localStorage` is one it can post anywhere.

### `POST /api/v1/auth/session`

Exchanges the operator token for a session. The only route where a credential is
*established*, so it checks the token itself before doing anything.

- Request: `Authorization: Bearer <operator token>`. No body. The token is never
  accepted in a body or query string — a query string is logged by every proxy
  in the world.
- `200`:
  ```json
  { "authenticated": true, "method": "session",
    "csrfToken": "9Xk…", "expiresAt": "2026-07-28T22:15:30Z" }
  ```
  plus `Set-Cookie: mcorch_session=…; Path=/; Max-Age=43200; HttpOnly;
  SameSite=Strict[; Secure]`
- `401 UNAUTHENTICATED` — wrong or missing token.

### `GET /api/v1/auth/session`

Who am I, and which CSRF token should I be sending. Call this on page load.

- `200` with the same shape. `method` is `"session"` or `"bearer"`; for a bearer
  caller `csrfToken` and `expiresAt` are `null`.
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
| `NOT_FOUND` | 404 | | no such server, secret or endpoint |
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

Affected: `paper.build`, `network.hostPort`, `network.rcon`, `resources.cpu`,
`storage.volume.size`, `placement.node`, and `metadata.labels` when empty. Read
them as `spec.network.rcon ?? { enabled: false }`.

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
loop drains the server before replacing it. Metadata-only changes (labels) do not
move `generation` and cause no drain.

### `DELETE /api/v1/servers/{name}`

**A drain request, not a stop.** Returns immediately.

```json
{ "accepted": true,
  "message": "the delete was recorded. The reconcile loop drains the server — evacuating players and confirming a world save — before anything is stopped, and frees the name only when that has finished. Poll this server, or watch the event stream, until it reports 404 NOT_FOUND",
  "server": { /* the resource, now terminating */ } }
```

- `202 Accepted` + `ETag`. Repeating it is a no-op that answers `202` again.
- `404` if the name is unknown. `409` if `If-Match` was sent and does not match.
- **No force flag.** There is no way to make this stop a server faster.

What a client should do afterwards:

1. Keep the row. Render `display.state: "TERMINATING"` and, once the loop starts,
   `status.drain.state` — `SEALED`, `TRANSFERRING`, `SAVING`, `DEREGISTERED`,
   `STOPPING`.
2. Poll the resource, or watch the stream for the `removed` event.
3. The row is gone when `GET` returns `404`. That happens when `:core` has
   confirmed the containers are gone and freed the name — **the API cannot do it
   and does not expose a way to.**
4. `status.drain.state: "DRAIN_FAILED"` means the drain aborted **and the server
   is still running**. There is no edge from there to a stop. It needs an
   operator.

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
  "detail": "" }
```

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
   `RUNNING`→`READY` if `status.ready` else `RUNNING`,
   `DRAINING`→`DRAINING`, `STOPPING`→`STOPPING`, `STOPPED`→`STOPPED`

`RUNNING` vs `READY` is a real distinction: running is not joinable.

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

**`drainBlocked` and `needsAttention` are never both true**, and that is the
whole reason `drainBlocked` exists. A drain that is not advancing shows
`drainState: "DRAIN_FAILED"` whether it is stuck or merely waiting — that state
means *parked*, not *broken* — so those two badges alone are indistinguishable,
and the only question an operator has about such a server is which of the two it
is. Render it as a tri-state beside the badge:

```ts
const drain =
  display.needsAttention ? 'needs a human'
  // A failed pass leaves the block intact, so this has to come first — see
  // the note on `drainBlocked` above.
  : server.status?.failure ? 'not progressing'
  : display.drainBlocked ? 'waiting for players'
  : 'in progress';
```

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
  "kinds": ["PaperServer"],
  "enums": {
    "phase": [...], "drainState": [...], "conditionType": [...], "conditionStatus": [...],
    "failureReason": [...], "failureClass": [...], "drainBlockReason": [...],
    "displayState": [...],
    "statePart": ["DESIRED", "OBSERVED"],
    "storageMode": ["persistent", "ephemeral"],
    "drainPolicy": ["waitForZeroPlayers"]
  },
  "limits": { "maxBodyBytes": 1048576, "maxStreams": 16 },
  "stream": { "path": "/api/v1/stream", "changePollMillis": 500, "statusPollMillis": 2000,
              "keepAliveMillis": 15000, "maxLifetimeMillis": 1800000, "reconnectMillis": 3000 } }
```

#### Two spellings, and the split is not cosmetic

- **`phase`, `drainState`, `conditionType`, `conditionStatus`, `failureReason`,
  `failureClass`, `drainBlockReason`, `displayState`, `statePart`** appear in
  *observed state* and are spelled by their Kotlin name: `RUNNING`,
  `DRAIN_STALLED`, `OBSERVED`.
- **`storageMode`, `drainPolicy`** appear in a *definition* and are spelled by
  their YAML wire value: `persistent`, `waitForZeroPlayers`. A form that offered
  `PERSISTENT` would build a document the parser rejects.

The key name tells you which: `…State`/`…Type`/`…Reason`/`…Class` are read back,
`storageMode`/`drainPolicy` are sent.

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

**Metrics, audit log, per-user roles, pagination.** Not needed at this scale.
`GET /api/v1/servers` returns everything; the change feed is the incremental path.

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
- **No secret material, ever.** `spec.network.rcon.passwordSecret` is
  `{name, key}` — coordinates. There is no endpoint that resolves them.
- **`status.failure.message` and `status.drain.failure.message`** are
  operator-facing and already redacted upstream for the CRI operations whose
  request carries a secret. There is no second, unredacted view and no raw-state
  endpoint.

- **`unreadable.reason`**, on a resource, in a listing and in a
  `SERVER_UNREADABLE` error, is the store's own operator-facing text. It names
  the server and what about the stored form was rejected, and it carries no stack
  trace, no class name, no SQL and no file path — `:store` does not put them in
  the value, and nothing here reaches past it to the exception to add them.

`ResponseLeakageTest` enforces all of this against every response body an
operator can obtain, with control assertions proving the search could have
failed; `StoreFailureTest` does the same for the unreadable paths, which no real
store will produce on demand.

---

## 14. TypeScript

```ts
export type ApiVersion = 'mcorch.dev/v1alpha1';
export type Kind = 'PaperServer';

export type ServerPhase =
  | 'PENDING' | 'IMAGE_PULLING' | 'CREATING' | 'STARTING' | 'RUNNING'
  | 'DRAINING' | 'STOPPING' | 'STOPPED' | 'FAILED' | 'UNKNOWN';

export type DrainState =
  | 'DRAIN_REQUESTED' | 'SEALED' | 'TARGET_RESOLVED' | 'TRANSFERRING'
  | 'SAVING' | 'DEREGISTERED' | 'STOPPING' | 'DRAIN_FAILED';

export type DisplayState =
  | 'PENDING' | 'STARTING' | 'RUNNING' | 'READY' | 'DRAINING'
  | 'TERMINATING' | 'STOPPING' | 'STOPPED' | 'FAILED'
  /** The stored observation will not decode. NOT the same as UNKNOWN — see §7. */
  | 'UNREADABLE'
  | 'UNKNOWN';

export type ConditionType =
  | 'IMAGE_AVAILABLE' | 'VOLUME_BOUND' | 'CONTAINER_RUNNING' | 'READY'
  | 'DRAINING'
  /** Parked and nothing is wrong. The inverse of NEEDS_ATTENTION — see §7. */
  | 'DRAIN_BLOCKED'
  | 'PLAYERS_EVACUATED' | 'WORLD_SAVED' | 'NEEDS_ATTENTION';

export type ConditionStatus = 'TRUE' | 'FALSE' | 'UNKNOWN';
export type FailureClass = 'RETRYABLE' | 'PERMANENT';

/**
 * Why a drain has stopped advancing when nothing has gone wrong. Not a
 * FailureReason, and deliberately not one: see DrainBlock below.
 */
export type DrainBlockReason = 'AWAITING_ZERO_PLAYERS';

/** Which half of a server's stored state something is about. */
export type StatePart = 'DESIRED' | 'OBSERVED';

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
export interface DefinitionInput {
  apiVersion: ApiVersion;
  kind: Kind;
  metadata: { name: string; labels?: Record<string, string> };
  spec: PaperServerSpecInput;
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
    /** Omit for no RCON. `passwordSecret` is required once `enabled` is true. */
    rcon?: { enabled?: boolean; port?: number; passwordSecret?: SecretRef };
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
export interface Definition {
  apiVersion: ApiVersion;
  kind: Kind;
  metadata: { name: string; labels?: Record<string, string> };
  spec: PaperServerSpec;
}

export interface PaperServerSpec {
  image: string;                                   // pinned: a tag or a digest, never `latest`
  paper: { minecraftVersion: string; build?: number };
  eulaAccepted: true;
  maxPlayers: number;
  network: {
    port: number;
    hostPort?: number;
    rcon?: { enabled: true; port: number; passwordSecret: SecretRef };
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

/** Absent optional fields are `null` here, not omitted. */
export interface ServerStatus {
  apiVersion: ApiVersion; kind: Kind; name: string;
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
  deregisteredAt: string | null;
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
    detail: string;
  };
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
