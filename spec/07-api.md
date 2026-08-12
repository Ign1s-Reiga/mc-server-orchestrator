# 07 — The HTTP contract

Responses carry typed, parsed results per [04-output.md](04-output.md). Raw RCON
text never crosses this boundary.

This section, once built, belongs in `api/API.md` — with its §11 amended to match
(roles and an audit log stop being absent). **§13 needs no amendment**, which is
the point of the output decision.

---

## 1. The rule that does not apply here

`api/API.md` §1 tells every client that *a 2xx means the request was recorded,
not that the world has changed*. **For the console it is the opposite.** A `200`
means the command ran on the server. There is no reconcile loop behind it and no
status to watch afterwards.

This is the only endpoint in the API for which that is true, and a dashboard that
treats a console response the way it treats a `PUT` will be wrong about both. See
[01-impact.md](01-impact.md) §1.

## 2. Why the command travels in the body

Two reasons, and the second is the one that must survive a refactor.

**No JSON parser exists here.** `api/build.gradle.kts` records that nothing in
`:api` parses JSON — the two structured request bodies today are a server
definition (parsed by `:schema`, YAML 1.2 being a strict superset of JSON) and
secret material (read as raw bytes so it is never bound into an intermediate
`String`). A console request carries one string, sent as **`text/plain`** and
read as bytes, so it needs no parser.

**A query string is logged by every proxy.** `api/API.md` already applies this to
the operator token, and `ApiServer.kt:176` logs `requestURI.rawPath` — the path
without the query — so the API's own lines cannot carry one either. It matters
more here than it does for a credential: [04-output.md](04-output.md) §2
guarantees no identity in a *response*, but `kick Alice` puts one in the
**request**. The body is the only place it does not end up in an access log. See
[08-origin-and-client.md](08-origin-and-client.md) §2.

The server name stays in the path — a declared object's name, not an identity.

Responses are JSON, written by the existing writer.

## 2.1 `X-Mcorch-Client`

Optional on every route, including this one. Carries a client name and the API
contract version it was built against — `dashboard/1` — so the API can refuse a
skewed client explicitly instead of failing in pieces.

It is **not** evidence about the caller: one `curl -H` forges it. Nothing
authorises on it, and it is recorded in the audit as *claimed*. When something
genuinely needs to know whether the dashboard is calling, the credential type
answers that and is already trusted. It cannot be made mandatory, because
`EventSource` cannot set headers and `GET /api/v1/stream` depends on that. Full
reasoning in [08-origin-and-client.md](08-origin-and-client.md) §3.

---

## 3. `POST /api/v1/servers/{name}/console`

Runs one command. Mutating, so a cookie-authenticated caller sends
`X-CSRF-Token`.

```http
POST /api/v1/servers/survival-01/console
Content-Type: text/plain

list
```

The body is a single command line with no leading slash, trimmed, capped well
below `maxBodyBytes`. A body containing a newline is a `400` — one request, one
command, so that an audit record and a refusal always refer to exactly one thing.

### Response

```json
{ "server": "survival-01",
  "command": "list",
  "tier": "viewer",
  "executedAt": "2026-08-12T09:14:02Z",
  "result": { "kind": "players", "online": 3, "max": 20 } }
```

`command` is the **matched allow-list entry**, not the raw input — the same value
the audit sink records, and for the same reason (see
[04-output.md](04-output.md)). `tier` is the effective tier the request ran at,
after the per-server ceiling was applied.

`result` is a typed, parsed shape drawn from the parser set in
[04-output.md](04-output.md) §3. It never carries a player name, UUID or client
address, so `api/API.md` §13 continues to hold structurally and
`ResponseLeakageTest` covers this endpoint with no carve-out.

Note the asymmetry ([04-output.md](04-output.md) §2): a moderation command's
**request** carries a player name as an argument, while no **response** ever
does. A dashboard cannot list who is online, so it cannot offer a player to
click — the operator supplies the name from elsewhere.

### Errors

| code | status | carries | meaning |
|---|---|---|---|
| `CONSOLE_NOT_CONFIGURED` | 409 | | `spec.network.rcon` is `Disabled` — no channel exists to carry a command |
| `CONSOLE_UNKNOWN_COMMAND` | 422 | | not in the allow-list at all |
| `CONSOLE_FORBIDDEN` | 403 | `requiredTier` | in the allow-list, above the caller's effective tier |
| `CONSOLE_COMMAND_REFUSED` | 409 | `useInstead` | Gate 1. Refused for every identity and tier |
| `CONSOLE_BUSY` | 503 | `Retry-After` | a drain is in flight, or the per-server queue is full. **Retryable, and safe to retry** |
| `CONSOLE_UNAVAILABLE` | 503 | `Retry-After` | the relay is unreachable, or the container is not running. **Retryable, and safe to retry** |
| `CONSOLE_TIMEOUT` | 504 | | the command outran its deadline. **Not safe to retry** — see §5 |
| `CONSOLE_OUTPUT_UNPARSED` | 502 | | The command **ran**; its output did not parse. **Not safe to retry** — see §5 |

`CONSOLE_COMMAND_REFUSED` carries the declarative alternative:

```json
{ "code": "CONSOLE_COMMAND_REFUSED",
  "message": "`stop` is refused on every console. Stopping a server goes through the drain.",
  "useInstead": { "method": "DELETE", "path": "/api/v1/servers/survival-01" } }
```

## 4. `GET /api/v1/servers/{name}/console`

What this caller may do here, so the dashboard renders an accurate console rather
than discovering its limits from `403`s.

```json
{ "server": "survival-01",
  "available": true,
  "tier": "operator",
  "commands": ["list", "say", "tps", "whitelist", "kick"] }
```

When RCON is disabled the capability is reported absent rather than `404` — open
decision 3 in [README.md](README.md):

```json
{ "server": "survival-01",
  "available": false,
  "reason": "RCON_DISABLED",
  "tier": "operator",
  "commands": [] }
```

`commands` is already filtered to the caller's effective tier, so it is directly
renderable. Gate 1 refusals never appear in it at any tier.

---

## 5. Two things a client must get right

**A `504` does not mean the command did not run.** It means no reply arrived
before the deadline. The command may have executed, may still be queued on the
main thread, and may execute afterwards. RCON offers no way to distinguish these,
and there is no request id to reconcile against later.

So `CONSOLE_TIMEOUT` is **not safe to retry automatically**, and a dashboard must
not do so. Show it to the operator and let them decide. This matters most for
exactly the commands most likely to time out — expensive ones on a busy modded
server — which are also the ones most likely to be side-effecting.

The two `503`s are different: both are refusals issued *before* dispatch, so
nothing ran and a retry is safe.

**There is no scrollback.** RCON is request/reply and returns nothing between
commands. A console built on this shows the replies to commands the operator
typed, and nothing else — no join and leave messages, no server log, no plugin
output. Live server output is container logs, which `api/API.md` §11 lists as
deliberately absent and for which `CriClient` and `Node` have no method today.

Separate, unbuilt work — though [01-impact.md](01-impact.md) §2's edge is its
main blocker, and this feature pays that cost anyway.

---

## 6. Route table

`RouteTableTest` asserts no route pattern contains `stop`, `kill`, `force` or
`purge`. Both routes here satisfy that unchanged.

That test is necessary and not sufficient for this feature: a route named
`/console` passes it while carrying `stop` in its body. The equivalent guard for
the console lives in Gate 1 — see [03-command-policy.md](03-command-policy.md)
§1.1 — and it deserves a test asserting the *command policy* refuses those verbs,
written alongside the route-table one so the pair reads as a single intention.
