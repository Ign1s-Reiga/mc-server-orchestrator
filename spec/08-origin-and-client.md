# 08 — Origin, and telling clients apart

Two decisions that shape the endpoint but are not about the console specifically:
where the dashboard is served from, and what the API can know about who is
calling it.

---

## 1. Same origin, and what actually follows from it

**Requirement: the dashboard and the API are served from the same origin.**

`api/API.md` already assumes it. Its CORS table treats an `Origin` matching the
request's `Host` as **"the normal deployment"**, and `MCORCH_API_ALLOWED_ORIGINS`
defaults to empty. Same-origin also avoids a constraint that file spells out: a
cross-site dashboard needs `SameSite=None`, which needs `Secure`, which needs
TLS. Same-origin keeps the loopback plain-HTTP deployment viable, which is what
threat-model item 1 leans on.

What is missing is that **nothing serves the SPA**. `:api` has no static file
handling.

### 1.1 The API serves the bundle

Static files from a configurable root — `MCORCH_API_STATIC_ROOT`, empty meaning
API-only so today's behaviour is unchanged. One process, one origin, no second
container, no reverse proxy.

Rejected alternatives, recorded so they are not re-proposed:

| | Why not |
|---|---|
| The dashboard as its own container | A container on its own port is a *different origin*, so it undoes the requirement it was meant to serve. It also forces the API off loopback — a sandbox in its own netns cannot reach the host's loopback — and creates a bootstrap hole where a sick orchestrator is exactly when the dashboard disappears |
| Toggled by `crictl` | `crictl` appears nowhere in this source. In the drain audits it appears only as the symptom of a failure to converge — *"unrecoverable-without-`crictl` is a finding"*. Making it a normal control surface inverts that, and invariant 7 puts every container operation through `Node` regardless |
| The SPA as a git submodule | Pulls a Node toolchain into a Gradle build whose module doc spends two sections justifying no HTTP framework and no JSON parser. Consume the built bundle instead, versioned against the contract |

If the dashboard should ever become an orchestrator-managed container, it becomes
a **declared kind** — schema, planner, reconcile logic, via the `add-server-kind`
procedure — not a toggle. A container the orchestrator does not know about is the
drift that reconciliation exists to remove.

### 1.2 What same-origin does *not* do

The browser's same-origin policy governs what page JavaScript may read from a
response. **It has no effect on what the server writes to a log.** Those
statements run identically whichever origin served the page; `Origin` is a
request header.

What removes the exposure is the *topology* — one process, loopback, no
intermediary. Same-origin enables that topology; it does not guarantee it. A
TLS-terminating proxy in front reinstates every access log it was meant to avoid.
That belongs in `docs/deployment.md`, because it is a deployment choice that
quietly undoes a property the design assumes.

## 2. Both halves of a console exchange are sensitive

[04-output.md](04-output.md) settles that raw server output crosses the boundary,
so a **response** carries player names, UUIDs and client addresses. The
**request** carries them too — `kick Alice`. Neither half may reach a place that
keeps a copy, and an intermediary's access log is the likeliest such place.

This is what makes same-origin load-bearing rather than merely tidy: it is what
removes the intermediaries.

This repository already reasons exactly this way about credentials.
`api/API.md`, on the operator token:

> The token is never accepted in a body or query string — a query string is
> logged by every proxy in the world.

And `ApiServer.kt:176` logs `exchange.requestURI.rawPath` — the path without the
query — so a query string cannot reach the API's own log lines either.

Three consequences:

- **The console command travels in the body, never the path or query.**
  [07-api.md](07-api.md) already puts it in a `text/plain` body for a different
  reason — `:api` parses no JSON — but this is the stronger reason and the one
  that must survive a future refactor.
- **The API never logs a request body.** It does not today. That becomes a
  property to assert rather than an accident to rely on.
- **The server name in the path is fine.** It is a declared object's name, not an
  identity.

---

## 3. Telling the dashboard apart from `curl`

### 3.1 A header cannot do this, and must never be trusted to

`curl -H 'X-Mcorch-Client: dashboard'` defeats any such check in one flag. A
custom header is **not** evidence of anything about the caller, and nothing may
authorise on it: not a tier, not a rate limit exemption, not a refusal.

### 3.2 The distinction that *is* authenticated already exists

`api/API.md` draws it, and it is backed by a credential rather than a claim:

| Credential | Who |
|---|---|
| `Cookie: mcorch_session=…` | the SPA. Issued only by `POST /api/v1/auth/session`, `HttpOnly`, tied to a session record |
| `Authorization: Bearer …` | scripts, `curl`, CI |

When something needs to know whether a caller is the dashboard, **this is the
signal to read**, not a header. It is already in the model and already trusted.

### 3.3 Where a client header does earn its keep

Not as identity — as a **declared contract version**, following the precedent
`ControlProtocol` already sets for the Velocity plugin: a `SUPPORTED` set, with
compatibility as *membership* rather than `>=`, because a set is the only rule
under which a newer server keeps serving an older client through a rolling
upgrade.

```http
X-Mcorch-Client: dashboard/1
```

- The API may refuse a client declaring a contract version it does not serve,
  with a clear error rather than the mysterious partial failures version skew
  otherwise produces.
- The console audit records it as **claimed, never verified** — see
  [04-output.md](04-output.md) §5. It is context for an operator reading the log
  later, not an authorisation input.
- Absent is legal and means "unknown client", never "rejected".

### 3.4 It cannot be required, and the reason is load-bearing

`api/API.md:84`:

> The cookie exists because `EventSource` cannot set headers — the live stream…

A blanket "every dashboard request carries this header" rule would break
`GET /api/v1/stream` for `EventSource` clients, which is the exact case the
cookie mechanism was built for. The header is therefore **optional on every
route**, and the stream endpoint neither requires nor expects it.

### 3.5 It adds little against CSRF, and that is fine

The custom-header trick — a browser cannot attach one cross-origin without a
preflight, and the preflight fails the origin allow-list — is real, and it is why
`Authorization` is CSRF-immune. But `X-CSRF-Token` is *already* a custom header
required on every cookie-authenticated mutation, so the property is already held
where mutations happen.

A second required header would duplicate it and add a way for a correct client to
fail. The value here is version negotiation and audit context, not CSRF.

---

## 4. What this changes elsewhere

| Document | Change |
|---|---|
| [07-api.md](07-api.md) | The body-not-query rule gains its stronger reason; `X-Mcorch-Client` documented as optional and unverified |
| [04-output.md](04-output.md) §5 | The audit record gains `client`, marked as claimed |
| `api/API.md` §12 | `MCORCH_API_STATIC_ROOT` |
| `docs/deployment.md` | Same-origin as the assumed topology, and the note that a TLS-terminating proxy reinstates the access-log exposure |
