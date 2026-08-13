# Remote console — specification

> **Status: proposed. Nothing in this specification is implemented.**
>
> It describes a feature that does not exist, and it contradicts four statements
> that are currently true of this system. Those contradictions are the subject of
> [01-impact.md](01-impact.md); they are not incidental and they are not cheap.
> Read it before anything else here.

**Goal:** let an operator run Minecraft console commands against a running server
from the dashboard, through RCON.

## Documents

| | |
|---|---|
| [01-impact.md](01-impact.md) | What this feature changes about the system. Four load-bearing statements it falsifies. **Read first.** |
| [02-relay.md](02-relay.md) | The per-node relay: placement, the `Node` seam, what `:cri` must surface, costs accepted |
| [03-command-policy.md](03-command-policy.md) | Two gates — invariant refusals, then permission tiers. The per-server ceiling |
| [04-output.md](04-output.md) | How command output crosses the API boundary. **Contains the decision everything else depends on** |
| [05-concurrency.md](05-concurrency.md) | Why the bottleneck is the Minecraft main thread, and the three rules that follow |
| [06-auth.md](06-auth.md) | What the console needs from multi-identity auth. The model itself is specified separately in [auth/](auth/README.md) |
| [07-api.md](07-api.md) | The HTTP contract the dashboard is written against |
| [08-origin-and-client.md](08-origin-and-client.md) | Same-origin hosting, why the request side is the exposure, and what the API can and cannot know about its caller |

Two related specifications live alongside this one:

| | |
|---|---|
| [auth/](auth/README.md) | Multi-identity authentication. The console's tier gate depends on it |
| [termination/](termination/README.md) | RCON becoming standard, and the forced-stop path. **Makes RCON universal, which is what makes the console universal** |

## Settled decisions

| Decision | Ruling | Why |
|---|---|---|
| Transport to RCON | Source RCON over TCP | RCON is not WebSocket-based; it is a length-prefixed binary TCP protocol with cleartext auth |
| Relay placement | Host-side, dialing the sandbox's CNI IP | Keeps the RCON port unpublished, per `PaperWorkload.kt:53` |
| Relay cardinality | One per node, serving every server on that node | Matches the `Node` seam; a global relay would need cross-node reach |
| Relay packaging | A component behind `Node`, not a separate deployable | Distributes with `Node` later; nothing new to deploy or version now |
| Sidecar alternative | Rejected | Adding a container changes the workload shape → spec hash → recreate → a drain for every existing persistent server |
| Drain's channel | Unchanged — keeps its own `ExecSync` path | A relay outage must not become a data-safety outage |
| Command policy | Two gates: invariant refusals, then tiers. `Member` and `Operator` allow-list; `Superuser` does not | An allow-list at every tier is what would stop a Forge mod's command working, so the top tier is bounded by Gate 1 alone |
| Concurrency | Parallel across servers, serial within one, subordinate to a drain | The bottleneck is the game's main thread, not relay capacity |
| Command output | **Raw server output crosses the boundary unmodified** | A general console cannot be built any other way. `api/API.md` §13 gains its one exception, written deliberately, with a matching `ResponseLeakageTest` carve-out. The endpoint returns unredacted output to authorised operators — it does not mask anything, and must never be described as if it did |
| Dashboard hosting | Same origin — `:api` serves the bundle from `MCORCH_API_STATIC_ROOT` | Already what the CORS table calls "the normal deployment"; avoids `SameSite=None`, which would force TLS. Not a second container: a different port is a different origin |
| Command transport | In the request body, never the path or query | The request carries the player name that the response never does; a query string is logged by every proxy |
| Client identification | The credential type, not a header | A header is forgeable with one `curl -H` flag. `X-Mcorch-Client` exists for contract-version negotiation and audit context only, is optional everywhere, and is never authorised on |
| Audit detail | Per server, via `spec.console.auditCommandText` | An argument can be a player name and the audit is a durable sink. Defaults to verb-plus-argument-count, which agrees with the logging rule; keeping full text is an operator overriding that for one server, written down in the manifest |

The output decision has three consequences worth carrying forward: the `Superuser`
tier is a general console bounded only by Gate 1, both the request and the
response carry player identities so neither may reach a place that keeps them,
and the audit sink stays redacted regardless — that is CLAUDE.md's logging rule,
which governs what is written to disk rather than what is returned to a caller.
See [04-output.md](04-output.md) §2–§3.

## Open decisions

1. **Behaviour when `spec.network.rcon` is `Disabled`.** Report the console as an
   absent capability so the dashboard can grey it out, or `404` the route. The
   first matches how `display` already communicates state; the second is less to
   specify. [07-api.md](07-api.md) drafts the first.

It does not block drafting or implementation.

## Sequencing

- [x] **Gate 1, the invariant refusal set** ([03-command-policy.md](03-command-policy.md)).
      `mcorch.core.console.ConsoleInvariants`. Independent of everything else and
      useful on its own, so it went first.
- [x] **~~Sandbox IP through `:cri` → `Node`~~** — **not needed.** Already
      present: `SandboxStatus.ips` carries it and `Node.callEndpoint` already
      dials it for the Velocity control channel. See
      [02-relay.md](02-relay.md) §4.1.
- [x] **The RCON wire codec** — `mcorch.core.console.rcon.RconCodec`. Frame only:
      no I/O, no connection state, so every bound is testable without a server.
- [x] **The RCON connection** — `RconConnection`. Auth handshake, multi-packet
      reassembly, and the per-server serialisation of
      [05-concurrency.md](05-concurrency.md) made structural by a `Mutex`.
- [x] **Static serving in `:api`** ([08-origin-and-client.md](08-origin-and-client.md) §1.1).
      `MCORCH_API_STATIC_ROOT`, off by default, with `/api/` and `/healthz` never
      shadowed by a file.
- [x] **The console channel on `Node`** — `Node.console`, shaped after
      `callEndpoint`. Connections are per call; session pooling is the
      optimisation [02-relay.md](02-relay.md) §4 describes and the interface
      admits unchanged.
- [ ] **An integration test for `LocalNode.console`** against the `mcorch-dev`
      containerd (`scripts/dev/containerd-up.sh`). It opens a socket, so it
      cannot be covered by a unit test; the protocol beneath it already is.
      **That instance is one shared daemon** — two concurrent runs collide and
      report a permanent create failure that is not yours, so this test needs
      run-unique server names.
- [ ] **Multi-identity auth** — specified separately in [auth/](auth/README.md).
      Nothing in Gate 2 can be built before it, and per
      [auth/03-authorization.md](auth/03-authorization.md) §5 the console's tier
      gate ships after the API-wide tier assignment, not before.
- [x] **Tier allow-lists** — `ConsolePolicy`. `Superuser` is unrestricted below
      Gate 1; the two lower tiers carry explicit sets.
- [ ] **The audit sink** ([04-output.md](04-output.md) §3), which lands with
      `spec.console.auditCommandText` — the field and its only consumer together.
- [x] **`spec.console.maxTier`** — landed with `ConsolePolicy`, which is what
      honours it. `auditCommandText` waits for the sink, for the same reason:
      a field nothing honours is the first failure `add-server-kind` names.
- [ ] **The console endpoint** — `POST /api/v1/servers/{name}/console`
      ([07-api.md](07-api.md)), which is what makes any of this reachable.
- [ ] **The contract into `api/API.md`** — §11 amended as roles and the audit log
      arrive, and **§13 amended to carve out the console**, which is the one
      endpoint that returns player names, UUIDs and client addresses.
      `ResponseLeakageTest` gains a matching exemption.

**The permission model is the long pole, not the RCON plumbing.** Multi-identity
auth is a project in its own right; the console channel is comparatively
mechanical now that `callEndpoint` has established its shape.
