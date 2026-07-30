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

`violations` and `conflict` are `null` unless the code carries them. Branch on
`code`, never on `message`.

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
all take a definition document.

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
  "caughtUp": false,              // status.observedGeneration === metadata.generation
  "display": { … }                // §7
}
```

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

`definition.spec` is the **effective** spec: the parser resolves every default, so
what comes back is what the reconciler acts on, not what the operator typed. A
`minimal.yaml` with four fields returns a spec with all of them.

### `GET /api/v1/servers`

```json
{ "cursor": "17", "count": 2, "items": [ /* resources */ ] }
```

Sorted by name. `cursor` is the change-feed position to open the stream from
(§8); it is read **before** the list, so a definition written between the two
reads appears in the stream rather than being missed by both.

Query parameters, all optional:

| parameter | form | meaning |
|---|---|---|
| `labelSelector` | `tier=survival,region=eu-west` | AND of equalities. `400` if a term has no `=`. |
| `state` | repeatable, e.g. `state=READY&state=DRAINING` | `display.state` |
| `terminating` | `true` \| `false` \| `any` (default) | |

### `GET /api/v1/servers/{name}`

`200` with the resource and `ETag`. `404 NOT_FOUND` if the name is unknown.
`400 BAD_REQUEST` if the segment is not a usable resource name.

### `GET /api/v1/servers/{name}/status`

The observation on its own, for a cheap poll of one server.

```json
{ "name": "…", "observedGeneration": 3, "generation": 3, "caughtUp": true,
  "recordedAt": "…", "resourceVersion": "…", "status": { … } }
```

`404` if the name is unknown **or** if nothing has been observed yet — the two
are distinguishable by the message, and by `GET /api/v1/servers/{name}` returning
`status: null`.

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
{ "state": "READY", "ready": true, "needsAttention": false,
  "drainState": null, "playersOnline": 3, "playersMax": 60,
  "detail": "" }
```

`state` is computed top-down and the order is the whole definition:

1. `metadata.terminating` → **`TERMINATING`** (outranks everything: a server
   showing `READY` while its name is being reclaimed is the one wrong answer that
   matters)
2. `status == null` → **`PENDING`**
3. a drain is in flight (`status.drain != null && state != DRAIN_FAILED`) →
   **`DRAINING`**
4. otherwise by `status.phase`:
   `FAILED`→`FAILED`, `UNKNOWN`→`UNKNOWN`, `PENDING`→`PENDING`,
   `IMAGE_PULLING`/`CREATING`/`STARTING`→`STARTING`,
   `RUNNING`→`READY` if `status.ready` else `RUNNING`,
   `DRAINING`→`DRAINING`, `STOPPING`→`STOPPING`, `STOPPED`→`STOPPED`

`RUNNING` vs `READY` is a real distinction: running is not joinable.

`needsAttention` is a **flag, not a state** — true when a `NEEDS_ATTENTION`
condition is `TRUE`. It reports and never authorises: a drain that has been
failing for an hour is still `DRAINING` with the flag beside it. Its
`lastTransitionAt` in `status.conditions` is what an alert should fire on.

`playersMax` falls back to `spec.maxPlayers` when nothing has been observed.

---

## 8. Live updates — `GET /api/v1/stream`

Server-sent events. Authenticate by **cookie** (`EventSource` cannot set
headers) or by bearer for a non-browser client.

### Why SSE and not a WebSocket

The dashboard needs server-to-client push and nothing else; every operator action
is a request with a status code and a body, which a WebSocket makes worse. SSE is
plain HTTP/1.1, so it inherits the cookie, the CORS decision and the reverse-proxy
config already in place, and `EventSource` reconnects and replays
`Last-Event-ID` with no client code. The one thing a WebSocket would buy —
sending a header on connect — is exactly what SSE cannot do either, which is why
the session cookie exists.

### Opening

```js
const es = new EventSource('/api/v1/stream', { withCredentials: true });
```

Optional `?cursor=<token>` resumes from a known position. With no cursor the
stream **opens with a full snapshot**, so a client needs no separate list call and
there is no window between listing and subscribing in which a change can be lost.
`?cursor=` wins over `Last-Event-ID`; `?cursor=` set to the empty string forces a
snapshot even on a browser reconnect.

### Events

Every event carries `id:` set to the current cursor, so a browser reconnect
resumes correctly whichever event arrived last. A client that handles `snapshot`,
`updated` and `removed` is already correct.

| event | data | do |
|---|---|---|
| `hello` | `{cursor, resumed, changePollMillis, statusPollMillis, keepAliveMillis, maxLifetimeMillis}` | note the cursor |
| `snapshot` | `{cursor, count, items:[resource]}` | replace the whole set |
| `updated` | `{name, reason, server}` | replace by name |
| `removed` | `{name, reason}` | delete by name |
| `expired` | `{cursor, message}` | nothing — a `snapshot` follows immediately |
| `bye` | `{reason:"MAX_LIFETIME", cursor}` | nothing — the browser reconnects |

`reason` on `updated` is `"definition"`, `"status"` or `"resync"`, for a human
reading a network tab rather than for branching on. `removed` means the drain
finished and `:core` purged the name — a *delete request* arrives as `updated`
with `terminating: true`.

`expired` is a real case a long-lived tab will hit: the change log is bounded, and
a connection that slept through enough writes cannot be told what it missed. A
client that ignores the event still converges, because the snapshot that follows
re-states everything.

Comment frames (`: keep-alive`) arrive every `keepAliveMillis`.

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

Every closed set the API can return, so the dashboard does not hard-code
enumerations it renders:

```json
{ "apiVersions": ["mcorch.dev/v1alpha1"], "currentApiVersion": "mcorch.dev/v1alpha1",
  "kinds": ["PaperServer"],
  "enums": { "phase": [...], "drainState": [...], "conditionType": [...],
             "failureReason": [...], "displayState": [...] },
  "limits": { "maxBodyBytes": 1048576, "maxStreams": 16 },
  "stream": { "path": "/api/v1/stream", "changePollMillis": 500, "statusPollMillis": 2000,
              "keepAliveMillis": 15000, "maxLifetimeMillis": 1800000 } }
```

A value added in `:schema` appears in the dashboard's filters without a frontend
release.

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

`ResponseLeakageTest` enforces all of this against every response body an
operator can obtain, with control assertions proving the search could have failed.

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
  | 'TERMINATING' | 'STOPPING' | 'STOPPED' | 'FAILED' | 'UNKNOWN';

export type ConditionType =
  | 'IMAGE_AVAILABLE' | 'VOLUME_BOUND' | 'CONTAINER_RUNNING' | 'READY'
  | 'DRAINING' | 'PLAYERS_EVACUATED' | 'WORLD_SAVED' | 'NEEDS_ATTENTION';

export type ConditionStatus = 'TRUE' | 'FALSE' | 'UNKNOWN';
export type FailureClass = 'RETRYABLE' | 'PERMANENT';

/** Absent optional fields are OMITTED here — see §6. Valid input to POST/PUT. */
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
  failure: FailureStatus | null;
}

export interface FailureStatus {
  reason: string; failureClass: FailureClass;
  message: string;                  // redacted upstream; no unredacted view exists
  occurredAt: string; attempts: number;
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
  caughtUp: boolean;
  display: {
    state: DisplayState; ready: boolean; needsAttention: boolean;
    drainState: DrainState | null;
    playersOnline: number | null; playersMax: number | null;
    detail: string;
  };
}

export interface ServerList { cursor: string; count: number; items: ServerResource[] }

export type ErrorCode =
  | 'BAD_REQUEST' | 'UNAUTHENTICATED' | 'CSRF_REQUIRED' | 'CSRF_INVALID'
  | 'ORIGIN_NOT_ALLOWED' | 'NOT_FOUND' | 'METHOD_NOT_ALLOWED' | 'SECRET_NOT_READABLE'
  | 'CONFLICT' | 'PAYLOAD_TOO_LARGE' | 'UNSUPPORTED_MEDIA_TYPE' | 'VALIDATION_FAILED'
  | 'PRECONDITION_REQUIRED' | 'INTERNAL' | 'STORE_UNAVAILABLE' | 'STREAM_LIMIT';

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
  };
}

export type StreamEvent =
  | { type: 'hello';    data: { cursor: string; resumed: boolean; changePollMillis: number;
                                statusPollMillis: number; keepAliveMillis: number; maxLifetimeMillis: number } }
  | { type: 'snapshot'; data: { cursor: string; count: number; items: ServerResource[] } }
  | { type: 'updated';  data: { name: string; reason: 'definition' | 'status' | 'resync'; server: ServerResource } }
  | { type: 'removed';  data: { name: string; reason: 'PURGED' } }
  | { type: 'expired';  data: { cursor: string; message: string } }
  | { type: 'bye';      data: { reason: 'MAX_LIFETIME'; cursor: string } };
```

### The three flows worth writing down

**Bootstrap.** `POST /auth/session` with the token → keep `csrfToken` in memory →
open `EventSource('/api/v1/stream', {withCredentials: true})` → build the table
from `snapshot`, apply `updated`/`removed`. No list call needed.

**Edit.** `GET /servers/{name}` → edit `definition` → `PUT` with
`If-Match: <ETag>` and `X-CSRF-Token`. On `409`, re-read, re-apply, retry. On
`422`, attach each violation to its `field`.

**Delete.** `DELETE /servers/{name}` → `202` → keep the row, render
`TERMINATING` and `status.drain.state` → the row goes when `removed` arrives or
`GET` answers `404`. If `drain.state` becomes `DRAIN_FAILED`, say so loudly: the
server is still running and needs an operator.
