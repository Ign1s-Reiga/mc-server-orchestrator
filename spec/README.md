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
| [06-auth.md](06-auth.md) | Multi-identity authentication — the prerequisite, and the long pole |
| [07-api.md](07-api.md) | The HTTP contract the dashboard is written against |
| [08-origin-and-client.md](08-origin-and-client.md) | Same-origin hosting, why the request side is the exposure, and what the API can and cannot know about its caller |

## Settled decisions

| Decision | Ruling | Why |
|---|---|---|
| Transport to RCON | Source RCON over TCP | RCON is not WebSocket-based; it is a length-prefixed binary TCP protocol with cleartext auth |
| Relay placement | Host-side, dialing the sandbox's CNI IP | Keeps the RCON port unpublished, per `PaperWorkload.kt:53` |
| Relay cardinality | One per node, serving every server on that node | Matches the `Node` seam; a global relay would need cross-node reach |
| Relay packaging | A component behind `Node`, not a separate deployable | Distributes with `Node` later; nothing new to deploy or version now |
| Sidecar alternative | Rejected | Adding a container changes the workload shape → spec hash → recreate → a drain for every existing persistent server |
| Drain's channel | Unchanged — keeps its own `ExecSync` path | A relay outage must not become a data-safety outage |
| Command policy | Allow-list, failing closed | You cannot safely handle output you cannot parse |
| Concurrency | Parallel across servers, serial within one, subordinate to a drain | The bottleneck is the game's main thread, not relay capacity |
| Command output | **Raw server output crosses the boundary unmodified** | A general console cannot be built any other way. `api/API.md` §13 gains its one exception, written deliberately, with a matching `ResponseLeakageTest` carve-out. The endpoint returns unredacted output to authorised operators — it does not mask anything, and must never be described as if it did |
| Dashboard hosting | Same origin — `:api` serves the bundle from `MCORCH_API_STATIC_ROOT` | Already what the CORS table calls "the normal deployment"; avoids `SameSite=None`, which would force TLS. Not a second container: a different port is a different origin |
| Command transport | In the request body, never the path or query | The request carries the player name that the response never does; a query string is logged by every proxy |
| Client identification | The credential type, not a header | A header is forgeable with one `curl -H` flag. `X-Mcorch-Client` exists for contract-version negotiation and audit context only, is optional everywhere, and is never authorised on |
| Audit detail | Per server, via `spec.console.auditCommandText` | An argument can be a player name and the audit is a durable sink. Defaults to verb-plus-argument-count, which agrees with the logging rule; keeping full text is an operator overriding that for one server, written down in the manifest |

The output decision has three consequences worth carrying forward: the `admin`
tier is a general console bounded only by Gate 1, both the request and the
response carry player identities so neither may reach a place that keeps them,
and the audit sink stays redacted regardless — that is CLAUDE.md's logging rule,
which governs what is written to disk rather than what is returned to a caller.
See [04-output.md](04-output.md) §2–§3.

## Open decisions

1. **Tier naming.** Orchestrator-native (`viewer`/`operator`/`admin`) as drafted
   in [03-command-policy.md](03-command-policy.md), or op-level numbers kept as
   familiar labels over the same sets.
2. **Behaviour when `spec.network.rcon` is `Disabled`.** Report the console as an
   absent capability so the dashboard can grey it out, or `404` the route. The
   first matches how `display` already communicates state; the second is less to
   specify. [07-api.md](07-api.md) drafts the first.

Neither blocks drafting or implementation.

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
- [ ] **The RCON connection** — auth handshake, multi-packet reassembly, and the
      per-server serialisation of [05-concurrency.md](05-concurrency.md).
- [ ] **Static serving in `:api`** ([08-origin-and-client.md](08-origin-and-client.md) §1.1).
      Independent of the console entirely, and the thing that makes same-origin
      real rather than assumed.
- [ ] **Multi-identity auth** — `:store` and `:api` ([06-auth.md](06-auth.md)).
      Nothing in Gate 2 can be built before this, because every credential in the
      system is currently the same token.
- [ ] **The `:core` edge and the console channel on `Node`**
      ([01-impact.md](01-impact.md) §2, [02-relay.md](02-relay.md)). Shaped after
      `callEndpoint`, which solves the same problem.
- [ ] **Tier allow-lists and the audit sink** ([04-output.md](04-output.md)).
      Gated by multi-identity auth. No parsers: `admin` is unrestricted below
      Gate 1, and the two lower tiers carry explicit sets.
- [ ] **`spec.console`** — `maxTier` and `auditCommandText`, `:schema` plus
      consumers in one change, per the `add-server-kind` procedure. Lands *with*
      the audit sink and the tier gate, never before them: a field the loop does
      not honour is the first failure that procedure names.
- [ ] **The contract into `api/API.md`**, and its §11 amended — roles and an
      audit log stop being absent. **§13 stands unchanged**, which is what the
      output decision bought.

**The permission model is the long pole, not the RCON plumbing.** Multi-identity
auth is a project in its own right; the console channel is comparatively
mechanical now that `callEndpoint` has established its shape.
