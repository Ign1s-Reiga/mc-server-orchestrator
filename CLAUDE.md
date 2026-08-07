# CLAUDE.md

A standalone, Minecraft-specific server orchestrator. It runs and manages Minecraft servers and proxies as containerd containers, reconciling them against a declared desired state — the way Kubernetes does, but purpose-built for Minecraft and depending on nothing but containerd.

This is not a Kubernetes operator. There is no k8s, no CRDs registered against an apiserver, no kubectl. We reimplement the reconcile idea ourselves.

## Stack

- Kotlin 2.x / JVM 25, Gradle Kotlin DSL (multi-module)
- containerd via the CRI gRPC API (`RuntimeService` / `ImageService`), stubs generated from `.proto` with the protobuf Gradle plugin + grpc-kotlin. We do not write Go.
- State in an embedded store (SQLite via JDBC), behind an interface so it can be swapped later
- API server for the dashboard backend (the SPA lives in a separate repo and is out of scope here)
- Tests: JUnit 5 + kotest assertions; integration tests against a real containerd

## Modules

```
:schema           Server-definition types (the CRD equivalent) + YAML parsing and validation
:cri              CRI client: generated stubs from .proto + a thin idiomatic wrapper
:core             Reconcile loop, scheduler, node abstraction (the seam for future distribution)
:store            State persistence behind an interface; a single-host implementation plugs in
:api              REST/gRPC API server (the dashboard backend)
:app              Wires everything into one runnable application
:velocity-plugin  The Velocity plugin, mounted into a proxy container — the control channel
                  for drain steps 2, 4 and 6
```

`:schema` and `:cri` are depended on widely. Breaking changes to either must update every consumer in the same change.

`:velocity-plugin` is the odd one out and is meant to stay that way. Its code does not run in the orchestrator's process — the JAR it builds is loaded by Velocity inside the proxy container — so it depends on no other module here, and it is the only module whose dependency (`velocity-api`, `compileOnly`) does not come from Maven Central. The arrow that is allowed points inward: `:core` may depend on it for the wire contract in `mcorch.velocity.control`, which names no Velocity type, so that the protocol version has one definition rather than a copy in the reconciler. Do not give it a dependency on `:schema` or `:core`.

## The distribution seam

Single host is the only target for now, but three things are abstracted from day one so a distributed version can be dropped in later. Do not collapse these abstractions just because there is currently one node.

1. **Node** — where a container runs. Today there is exactly one (localhost). Code addresses containers through a `Node` handle, never assuming locality.
2. **Scheduler** — decides which node a server lands on. Today it is trivial (always the one node). It is still a real interface with a real call site.
3. **Store** — desired and observed state. Today embedded/local. The interface must not leak SQLite specifics.

If a change makes one of these assume single-host in a way that a later distributed implementation could not satisfy, that change is wrong.

## Commands

```bash
./gradlew build                    # build and test everything
./gradlew :core:test               # reconcile-loop unit tests only
./gradlew :cri:generateProto       # regenerate CRI stubs from .proto (see the generate-cri-stubs skill)
./gradlew spotlessApply            # format
./gradlew :app:run                 # run the orchestrator locally
./gradlew :velocity-plugin:pluginJar   # build the proxy control plugin JAR (:core mounts this)

scripts/dev/containerd-up.sh       # start a local containerd for integration work
scripts/dev/containerd-down.sh
./gradlew :app:integrationTest     # integration tests against a real containerd (requires containerd-up.sh)
./gradlew :cri:integrationTest     # CRI-boundary tests against a real containerd (same prerequisite)
```

## Non-negotiable invariants

Breaking the ordering below loses player data. Check these first when implementing or reviewing.

1. **Never stop or remove a container that has players online without draining it first.** Scale-downs, restarts, and rescheduling all go through the drain protocol (`.claude/skills/drain-protocol/`). Do not put an unconditional container stop in any code path.
2. **Servers with world data get a persistent volume/mount that outlives the container.** Only disposable lobbies and minigame instances may be treated as ephemeral. Branch on the server definition and default to persistent (the safe side).
3. **Confirm the world save completed before stopping a container.** The container stop timeout is a last-resort safety net, not the normal save path. The stop grace period must always exceed the expected save duration.
4. **The Velocity forwarding secret only ever travels through the secret store.** Never put it in a server-definition YAML, a log line, or a test fixture. Do not propose any forwarding mode other than modern forwarding.
5. **Reconcile is idempotent.** Running it repeatedly against the same desired and observed state must not accumulate side effects (no duplicate containers, no repeated pulls, no repeated save requests). Record observed state after each pass.
6. **After changing the CRI `.proto` set, regenerate stubs via the generate-cri-stubs skill and commit the generated sources' build config together.** Do not hand-edit generated stubs.
7. **Every container operation goes through the Node abstraction.** Never call the CRI client with an implicit "the local one" assumption outside the single-host Node implementation.

## Coding conventions

- Kotlin explicit API mode. Public API gets explicit visibility and return types.
- `!!` and `lateinit` are banned. Model absence with a sealed class or `Result`.
- The reconcile loop must not block for long. When waiting on a container state, requeue with a backoff rather than sleeping in place.
- Structured logging. Never log player names, UUIDs, or IP addresses.
- Do not swallow exceptions. Retryable failures requeue; permanent failures surface on the server's observed status.
- Never delete a test to make the build pass. If the spec changed, rewrite the test and say why in the commit message.
- Treat CRI calls as failable and slow. Everything crossing the `:cri` boundary has timeouts and cancellation.

## Subagent routing

| Task | Route to |
|---|---|
| Understanding CRI / containerd / a Kotlin library | `docs-researcher` (external) / built-in Explore (this repo) |
| YAML schema and server-definition types | `schema-designer` |
| Reconcile loop, scheduler, node abstraction | `reconciler-dev` |
| CRI client and proto generation | `cri-integration-dev` |
| State persistence and the store interface | `store-dev` |
| API server | `api-dev` |
| Running tests and triaging failures | `test-runner` |
| Integration tests against real containerd | `integration-tester` |
| Auditing stop/drain safety | `drain-auditor` |
| Reviewing a change as a whole | `code-reviewer` |

- `reconciler-dev`, `cri-integration-dev`, `store-dev`, and `api-dev` run in isolated git worktrees; independent tasks can run concurrently. Serialize anything touching the same files.
- Always delegate test runs, log analysis, and bulk file reading to a subagent and have it return only a summary.
- Changes spanning `:schema` or `:cri` go to a single agent as one unit. Never leave one side updated and the other stale.

## Do not

- Introduce a dependency on Kubernetes, Docker Engine, or any container runtime other than containerd. CRI is the boundary.
- Collapse the Node / Scheduler / Store abstractions to single-host shortcuts.
- Write Go. The only generated non-Kotlin artifacts are the CRI stubs, produced by the build.
- Modify anything under `.git` or `.claude` on your own judgement.
- Merge drain-related code at the "seems to work" stage. It always goes through `drain-auditor` first.
