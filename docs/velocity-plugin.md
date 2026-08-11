# The proxy control plugin

Three steps of the drain protocol need the proxy to *act* rather than merely be
observed: stop new logins (step 2), transfer the players (step 4), and deregister
the backend (step 6). Nothing in the Velocity API lets an outside process do
that, so the orchestrator ships a plugin, mounts it into the proxy container, and
talks to it over a small HTTP control channel.

This is the only module whose code does not run in the orchestrator's process.
It runs inside *your* proxy container, which makes it operator-facing in a way
the other modules are not.

---

## What gets mounted, and under what name

```bash
./gradlew :velocity-plugin:pluginJar
```

produces `velocity-plugin/build/libs/mcorch-velocity-control-plugin.jar` — the
fat JAR, containing this module's classes plus `kotlin-stdlib` and **nothing
else**. Everything Velocity itself provides — Adventure, Gson, Guava, Guice,
SnakeYAML, slf4j — is `compileOnly` and deliberately absent, because a second
Adventure on the plugin's classloader is a runtime failure this build could not
see.

Copy it into `MCORCH_ASSET_DIR` under the name the node looks for:

```
$MCORCH_ASSET_DIR/mcorch-velocity-control.jar
```

The name is not the JAR's build name. `:core` asks for the asset
`mcorch-velocity-control.jar` and mounts it at `/plugins` inside the proxy
sandbox. A missing artefact is refused when the proxy is created — loudly, rather
than by starting a proxy with no control endpoint, which would look healthy right
up until the first drain.

Note the ordinary `jar` task produces a *thin* JAR, and that split is deliberate:
`:core` depends on this module for the wire contract, and if `jar` were the fat
one, `:app`'s runtime classpath would get a second copy of `kotlin-stdlib` with
classpath order deciding which wins. Consumers get the thin jar; the proxy gets
`pluginJar`.

---

## Configuration, inside the container

The plugin reads three variables from its own environment. `:core` sets them when
it plans the proxy workload; they are listed here because they appear in a
container an operator can inspect, and because a hand-run proxy needs them.

| Variable | Default | Meaning |
|---|---|---|
| `MCORCH_CONTROL_PORT` | `8375` | Port the control endpoint listens on, inside the sandbox |
| `MCORCH_CONTROL_BIND` | `0.0.0.0` | Bind address within the sandbox |
| `MCORCH_CONTROL_TOKEN` | unset | Bearer token required on every control request |

A blank `MCORCH_CONTROL_TOKEN` is rejected rather than treated as unset. The
distinction matters: an endpoint told nothing serves whoever reaches the port,
and a blank value is far more likely to be a broken template than a decision.

On the orchestrator side these come from the definition — `spec.control.port` and
`spec.control.tokenSecret` — and `spec.control.hostPort` decides whether the
endpoint is published beyond the sandbox at all. It is unpublished by default,
and publishing it makes the token mandatory: anything that can reach the control
port can seal backends and move every player in the fleet. See `docs/schema.md`.

---

## The wire contract

`mcorch.velocity.control.ControlProtocol` is the single definition of the
protocol, and `:core` depends on this module for it so that the version has one
definition rather than a copy in the reconciler. It names no Velocity type, which
is what makes that dependency safe.

| | |
|---|---|
| Protocol version | `1` |
| Plugin id / version | `mcorch-control` / `1.0.0` |
| Content type | `application/json; charset=utf-8` |
| Auth | `Authorization: Bearer <MCORCH_CONTROL_TOKEN>` |
| Max request body | 16 KiB |

Endpoints:

| Path | Purpose |
|---|---|
| `/v1/version` | Handshake. The one path served without authentication |
| `/v1/proxy` | Proxy-wide state |
| `/v1/state` | Current view of backends and player counts |
| `/v1/backends/{name}` | Register, seal, deregister a backend |
| `/v1/backends/{name}/transfer` | Move this backend's players |

`/v1/version` reports `pluginVersion` and `pluginApiVersion` separately: the
plugin's own release, and the protocol it speaks. `:core` checks the second.

---

## Version mismatch is a first-class failure

The orchestrator does not assume the plugin in the container is the one this
build ships. It asks, and if the plugin speaks a protocol this build does not
understand, the proxy reports `PROXY_PLUGIN_INCOMPATIBLE` and the
`CONTROL_ENDPOINT_READY` condition is false.

**That is not a proxy-only problem.** Seal, transfer and deregister are
unavailable, which means *no backend behind that proxy can complete a drain* —
so no server in the fleet can be edited in a way that reshapes it, or deleted.
A stale JAR in `MCORCH_ASSET_DIR` after an orchestrator upgrade is the usual
cause, and re-staging it is the fix.

`PROXY_CONTROL_UNREACHABLE` is the neighbouring failure: the endpoint did not
answer at all. Same consequence for drains, different cause — usually the
container, the port or the token rather than the version.

---

## Why HTTP and not a Velocity plugin message

A plugin messaging channel only carries traffic while a player is connected,
which is exactly wrong for a control channel whose job includes acting on a proxy
with nobody on it. The endpoint is `com.sun.net.httpserver` for the same reason
`:api` is: it is in the JDK, so the plugin JAR stays this module's classes plus
`kotlin-stdlib`, and nothing that ships into somebody else's proxy container can
go stale against an upstream release.
