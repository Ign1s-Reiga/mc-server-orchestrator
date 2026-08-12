# 01 — What this feature changes about the system

This is not an endpoint being added to the API. It is a change to what the API
is. Four statements the repository currently makes — in prose, in a build file
and in tests — become false the day a console ships.

Each is reversible on purpose. None should be reversed by accident.

---

## 1. The API stops being purely declarative

`api/API.md` §1, the first thing a client is told:

> **This API is a thin edge over a declarative core.** Every mutation writes
> *desired state* and returns. Nothing in it starts, stops or inspects a
> container.

A console command inspects and acts on a running container. It is imperative by
nature: there is no desired state for "run `/say hello` once", and no reconcile
loop can converge on it. The console would be the first endpoint in this API
whose effect is immediate rather than recorded, and §1's promise — *a 2xx means
the request was recorded, not that the world has changed* — is inverted for it
alone.

**Consequence.** The console must be presented to clients as a distinct kind of
endpoint, not as another row in §6. A dashboard that treats a console response
the way it treats a `PUT` will be wrong about both. [07-api.md](07-api.md)
carries that distinction into the contract.

---

## 2. The `:api` → `:core` edge

`api/build.gradle.kts` carries a section titled *"Still no :core edge"*, and
`:api` today references nothing under `mcorch.core`. Every mutation is a write to
desired state, which is why the module can depend on `:schema` and `:store` and
stop there.

A console command must reach a running container, which means reaching the relay,
which lives behind `Node`, which lives in `:core`. `api/API.md` §11 already
anticipates this pressure from the container-logs direction and rules on it:

> Adding it means adding a `:core` edge to `:api` — a real decision with a real
> justification, not something to slip in.

This feature is that decision arriving.

Make it explicitly and once. The same edge then makes container logs trivially
available, and **the console is a weaker justification for the edge than logs
are** — logs are read-only and carry no command authority. If the edge is going
to be added, it is worth deciding whether logs should be what justifies it.

---

## 3. The PII guarantee stops being structural

`api/API.md` §13 is the strongest guarantee in this API, and its strength is
entirely in *how* it holds:

> **No player names, UUIDs or client addresses.** Not by filtering — there is
> nothing in the objects to leave out.

Every response body is a typed object with no field an identity could occupy.
`status.players` is `{online, max, observedAt}`. `ResponseLeakageTest` enforces
this across every response an operator can obtain, with control assertions
proving the search could have failed.

RCON returns free-form text. `/list` returns
`There are 3 of a max of 20 players online: Alice, Bob, Carol`. A console that
returns raw RCON output returns a string, and a string has no shape to guarantee.

**This one is broken deliberately.** [04-output.md](04-output.md) settles that raw
output crosses the boundary, because a general console cannot be built any other
way. §13 becomes true of every endpoint except the console, the exception is
written into it as part of the same change, and `ResponseLeakageTest` gains an
explicit carve-out rather than discovering the collision during implementation.

The guarantee stops being *absolute* — which is most of what it was worth, since
an absolute claim is checkable and a qualified one has to be remembered. That
cost was weighed and accepted against a console that works on modded fleets.

---

## 4. Roles and audit stop being absent

`api/API.md` §11:

> **Metrics, audit log, per-user roles, pagination.** Not needed at this scale.

Both of the first two are prerequisites here. The justification for reversing
them is a change in the premise rather than a change of mind: planned Forge
support implies large fleets, and a console is a facility whose safe use depends
on knowing who used it.

`OperatorAuth` holds a **single** token digest today. Sessions are exchanged for
that one token, so every credential in the system carries identical authority —
`api/API.md` says it outright: *"There are no roles — any authenticated caller
can do anything the API offers."* There is nothing for a permission tier to
attach to.

Multi-identity auth is a prerequisite, not a detail. See [06-auth.md](06-auth.md).

---

## What stays true

Worth stating, because these are the guarantees the console must not erode:

- **`RouteTableTest` keeps passing unchanged.** No route pattern contains `stop`,
  `kill`, `force` or `purge`. A route named `/console` satisfies that test while
  carrying `stop` in its body — which is exactly why the guard has to move into
  the command policy. See [03-command-policy.md](03-command-policy.md).
- **The drain protocol is untouched.** It keeps its own `Node.exec` → `ExecSync`
  channel and does not learn about the relay. See [02-relay.md](02-relay.md).
- **Secret material is still never returned.** `passwordSecret` remains
  `{name, key}` coordinates and no endpoint resolves it. The relay resolving it
  internally is a separate cost, recorded in [02-relay.md](02-relay.md) §5.
- **The logging convention is untouched.** *Never log player names, UUIDs, or IP
  addresses* governs what the orchestrator writes to disk. The output decision is
  about a response body to an authenticated caller and does not reach it — the
  audit sink stays redacted. See [04-output.md](04-output.md) §3.
