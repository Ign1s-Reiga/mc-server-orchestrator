# mc-server-orchestrator

Runs and manages Minecraft servers and proxies as containerd containers, reconciling
them against a declared desired state — the way Kubernetes does, but purpose-built for
Minecraft and depending on nothing but containerd.

You write down the servers you want. A reconcile loop makes reality match, and reports
what it actually observed. The interesting part is not starting servers; it is stopping
them without losing anyone's world.

> **Status: early.** Single-host only, schema `v1alpha1`, no published artifacts. The
> reconcile loop, the drain protocol, both server kinds and the API are implemented and
> tested against a real containerd. Field names may still change; `v1alpha1` means what
> it means.

---

## Why this exists

A Minecraft server is not a container you can stop whenever you like. The last several
minutes of play live in memory, saving a world takes tens of seconds, and a kill in the
middle of a save corrupts region files. Connected players are simply disconnected unless
something moves them first.

So every ordinary orchestrator primitive — scale down, restart, reschedule, apply a
changed spec — is a data-loss event unless the stop is done properly. That procedure is
the centre of this project, and most of the design exists to make it impossible to skip.

## What it is not

- **Not a Kubernetes operator.** No apiserver, no CRDs, no `kubectl`. The reconcile idea
  is reimplemented directly.
- **Not tied to Docker.** CRI is the only container boundary, and containerd the only
  runtime.

---

## Declaring a server

```yaml
apiVersion: mcorch.dev/v1alpha1
kind: PaperServer
metadata:
  name: survival-01
spec:
  image: docker.io/itzg/minecraft-server:2026.6.1
  paper:
    minecraftVersion: "1.21.8"
  eulaAccepted: true
  resources:
    memory: 4Gi
```

Everything else has a default, and the defaults are on the safe side: storage is
persistent, drains wait for zero players, the proxy's control endpoint is not published.

A proxy is the other kind:

```yaml
apiVersion: mcorch.dev/v1alpha1
kind: VelocityProxy
metadata:
  name: front-door
spec:
  image: docker.io/itzg/mc-proxy:2026.5.2
  resources:
    memory: 1Gi
  forwarding:
    secret:                      # coordinates into the secret store, never a value
      name: velocity-forwarding
      key: secret
  backends:
    selector:
      matchLabels:
        fleet: survival
```

Every field, default, bound and validation message is documented in
**[docs/schema.md](docs/schema.md)**.

---

## The drain protocol

Every path that stops or removes a container goes through this. There is no
unconditional container stop anywhere in the codebase.

1. **Mark the drain started** — the loop stops treating the server as a placement target
   and stops healing it back to running.
2. **Stop new joins** — the proxy removes the backend from routing for *new* players.
   It is deliberately not deregistered yet; that would disconnect the players still on it.
3. **Secure a destination** — confirm somewhere with capacity exists. If none does, the
   drain aborts. Kicking players to make progress is not an option.
4. **Transfer the players** — the proxy moves them, with retries, and confirms either
   everyone moved or a zero-player reading.
5. **Save the world** — request it and *wait for the completion notification*. Sending
   the request is not evidence. On timeout the drain aborts and the container keeps
   running.
6. **Deregister the backend** — only after zero players and a confirmed save.
7. **Stop the container** — only now. The grace period is a last-resort net for a
   container disappearing outside this protocol, never the normal save path.

If a step fails, the container **keeps running** and the reason is recorded on observed
status. Reaching a retry limit is never a reason to force-stop.

Steps 2, 4 and 6 need the proxy to act, which is what `:velocity-plugin` is for: a plugin
mounted into the proxy container that gives the reconciler a control channel.

The consequences an operator actually meets — including the persistent server with RCON
disabled that deliberately cannot be deleted — are in
**[docs/operating.md](docs/operating.md)**.

---

## Architecture

| Module | Responsibility |
|---|---|
| `:schema` | Server-definition types and YAML parsing. Validation happens here, at parse time, so an invalid definition never reaches the loop. |
| `:cri` | CRI client — gRPC stubs generated from the containerd `.proto` set, behind a small idiomatic wrapper. |
| `:core` | The reconcile loop, the drain state machine, the scheduler, and the node abstraction. |
| `:store` | Desired and observed state behind an interface; SQLite for the single-host implementation. |
| `:api` | REST API — the dashboard backend. The SPA lives elsewhere. |
| `:app` | Wires it together into one runnable process. |
| `:velocity-plugin` | The plugin loaded by Velocity inside the proxy container. The control channel for drain steps 2, 4 and 6. |

### The distribution seam

Single host is the only target today, but three things are abstracted from the start so a
distributed implementation can be dropped in without rewriting the loop:

- **Node** — where a container runs. Today there is exactly one. Code addresses containers
  through a `Node` handle and never assumes locality.
- **Scheduler** — which node a server lands on. Trivial today, but a real interface with a
  real call site.
- **Store** — desired and observed state, with no storage-engine specifics in the
  interface.

These are deliberately not collapsed to single-host shortcuts.

---

## Building and running

Requires **JDK 25** (the build provisions a toolchain) and, for anything that touches
containers, a local **containerd** with the CRI plugin enabled.

```bash
./gradlew build                 # compile and test everything
./gradlew :app:run              # run the orchestrator locally
./gradlew spotlessApply         # format
```

Against a real containerd:

```bash
scripts/dev/containerd-up.sh    # start a local dev containerd
./gradlew :app:integrationTest  # loop and drain, end to end
./gradlew :cri:integrationTest  # CRI boundary behaviour
scripts/dev/containerd-down.sh
```

Other useful targets:

```bash
./gradlew :velocity-plugin:pluginJar   # the proxy control plugin (:core mounts this)
./gradlew :cri:generateProto           # regenerate CRI stubs from .proto
```

## Tests

**1028 unit tests** across the seven modules, plus integration suites in `:app` and
`:cri` that run against a real containerd rather than a fake.

Two mutation harnesses guard the parts where a passing test could still be measuring
nothing:

```bash
scripts/dev/drain-wiring-mutations.sh     # 96 mutations of the drain wiring
scripts/dev/control-plugin-mutations.sh   # 12 mutations of the proxy control plugin
```

Each plants a deliberate defect, rebuilds, and fails unless the specific test that claims
to catch it does. A rule that nothing can break is not a rule.

---

## Documentation

- **[docs/schema.md](docs/schema.md)** — writing definitions: every field of both kinds
  with its default and bounds, the cross-field rules and what they say when they fire, the
  secret-reference contract, and what gets clamped rather than rejected.
- **[docs/operating.md](docs/operating.md)** — behaviours that are deliberate, correct and
  surprising, and what to do when a drain will not finish.
- **[docs/server-lifecycle.md](docs/server-lifecycle.md)** — the status model a client
  renders: which phases and conditions follow which, what a drain reports at each step,
  and the distinctions that must not be collapsed into one badge.
- **[docs/troubleshooting.md](docs/troubleshooting.md)** — a symptom index, from "the
  client cannot connect" to "the edit was accepted and nothing happened".
- **[docs/deployment.md](docs/deployment.md)** — every environment variable with its
  default, what happens when one is missing, and a first run that works.
- **[docs/reconcile.md](docs/reconcile.md)** — the loop's contract: how it finds work,
  the rules a pass obeys, and where it deliberately stops.
- **[docs/store.md](docs/store.md)** — desired and observed state, the on-disk migrations,
  and the secret-store contract.
- **[docs/velocity-plugin.md](docs/velocity-plugin.md)** — the control channel mounted into
  the proxy: what to stage where, its three variables, and the protocol handshake.
- **[docs/cri.md](docs/cri.md)** — the only place this project talks to a runtime: the
  deadline budgets, the classified failure hierarchy, and why some error text is withheld.
- **[docs/state-machine.md](docs/state-machine.md)** and
  **[docs/failure-modes.md](docs/failure-modes.md)** — the drain, normatively: every state
  with its timeout, and the implementations that look reasonable and lose player data.
  Source cites the failure modes by item number.
- **[CLAUDE.md](CLAUDE.md)** — architecture rules and the invariants that must hold.
- **[api/API.md](api/API.md)** — the HTTP API.

## Stack

Kotlin 2.4.10 on a JVM 25 toolchain, Gradle Kotlin DSL. gRPC 1.82.1 with grpc-kotlin and
protobuf 4.35.1 for the CRI stubs. SQLite (JDBC) for the embedded store. JUnit 5 and
kotest for tests.

## License

[MIT](LICENSE).
