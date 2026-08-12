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
| Command output | Typed parsers; raw RCON text never crosses the boundary | Keeps `api/API.md` §13's no-PII guarantee structural and absolute, with no `ResponseLeakageTest` carve-out |

The output decision has two consequences worth carrying forward: the console is a
set of typed operations rather than a general console, and the allow-list is
bounded by which parsers exist. See [04-output.md](04-output.md) §1–§2.

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

1. **Multi-identity auth** — `:store` and `:api` ([06-auth.md](06-auth.md)).
   Nothing in Gate 2 can be built before this, because every credential in the
   system is currently the same token.
2. **Gate 1, the invariant refusal set** ([03-command-policy.md](03-command-policy.md)).
   Independent of everything else and useful on its own.
3. **Sandbox IP through `:cri` → `Node`** ([02-relay.md](02-relay.md)).
4. **The `:core` edge and the relay** ([01-impact.md](01-impact.md) §2, [02-relay.md](02-relay.md)).
5. **Allow-list, parsers and the audit sink** ([04-output.md](04-output.md)).
6. **`spec.console` ceiling** — `:schema` plus consumers, in one change.
7. **The contract into `api/API.md`**, and its §11 amended — roles and an audit
   log stop being absent. **§13 stands unchanged**, which is what the output
   decision bought.

Steps 2 and 3 are independent and can run concurrently. Step 1 gates step 5.

**The permission model is the long pole, not the RCON plumbing.** Step 1 is a
project in its own right; steps 3 and 4 are comparatively mechanical.
