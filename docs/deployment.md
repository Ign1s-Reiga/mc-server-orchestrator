# Running the orchestrator

Everything the process needs is read from the environment before anything is
opened. There is no configuration file: one deployment shape, and every value has
to be settled before a socket is bound or a database handle exists, so a format
would be a parser to get wrong.

Configuration is **total**. The process either starts with a usable configuration
or exits **78** having said which variable is at fault. It never starts degraded.

---

## The shortest thing that works

```bash
MCORCH_CRI_ENDPOINT=unix:///run/mcorch-dev/containerd.sock \
MCORCH_DATA_DIR=/var/lib/mcorch \
MCORCH_API_LISTEN=127.0.0.1:8080 \
MCORCH_API_TOKEN="$(head -c 32 /dev/urandom | base64)" \
  app/build/install/app/bin/app
```

Build that launcher with `./gradlew :app:installDist`. Prefer it to
`./gradlew :app:run`, which is a `JavaExec` inheriting the Gradle daemon's
environment — command-line variables are not reliably propagated to a reused
daemon.

Two of those four have no default and the process will not start without them:
`MCORCH_CRI_ENDPOINT`, and `MCORCH_API_TOKEN` unless the API is switched off.

---

## Orchestrator

| Variable | Default | Meaning |
|---|---|---|
| `MCORCH_CRI_ENDPOINT` | **none — required** | The containerd CRI socket this orchestrator manages, e.g. `unix:///run/mcorch-dev/containerd.sock` |
| `MCORCH_DATA_DIR` | `/var/lib/mcorch` | State and secret databases. `volumes/` and `logs/` live under it |
| `MCORCH_NODE_NAME` | `local` | How this node is addressed. Not a hostname lookup — you name it |
| `MCORCH_ASSET_DIR` | `<data>/assets` | Artefacts mounted into containers; today the Velocity control plugin JAR |
| `MCORCH_CGROUP_PARENT` | `mcorch.slice` | See below — the wrong shape fails every sandbox |
| `MCORCH_VELOCITY_BUILD` | the build this orchestrator ships against | Pins every proxy to one Velocity build |

### `MCORCH_CRI_ENDPOINT` has no default on purpose

Guessing `/run/containerd/containerd.sock` would point a fresh deployment at
whatever containerd the host already runs — Docker's, or Kubernetes' — and the
first thing this orchestrator would do is list *its* sandboxes and start
reconciling containers it did not create. So there is no default, and the error
message says as much.

The CRI channel is opened **lazily** and no RPC is issued at startup, so the
process starts and serves its whole API with no containerd present. That is
useful for developing a client and confusing if you expected containers: servers
sit in `PENDING` with a retryable `NODE_UNAVAILABLE`.

### `MCORCH_CGROUP_PARENT` depends on the cgroup driver

Its shape has to match how the runtime actually manages cgroups.

| Driver | Value |
|---|---|
| systemd (cgroup v2) | `mcorch.slice` — a slice name, the default |
| cgroupfs | `/mcorch` — a path fragment |

A slice name on a cgroupfs host creates a directory literally called
`mcorch.slice`. Leaving it unset is not the same as the default: unset leaves the
field blank, which is containerd's own default, and on a systemd host that is
exactly what makes every `RunPodSandbox` fail. The slice itself need not exist —
systemd creates a transient one on demand.

### Data layout

```
<MCORCH_DATA_DIR>/
  state.db      definitions, observed status, the change feed
  secrets.db    secret material — see docs/store.md
  volumes/      persistent world data; outlives every container
  logs/         container logs
  assets/       default MCORCH_ASSET_DIR
```

`volumes/` is the one directory whose loss is unrecoverable. Everything else the
orchestrator can rebuild by observing the runtime.

Before running proxies, stage the control plugin where the node expects it:

```bash
./gradlew :velocity-plugin:pluginJar
cp velocity-plugin/build/libs/mcorch-velocity-control-plugin.jar \
   "$MCORCH_ASSET_DIR/mcorch-velocity-control.jar"
```

The filename matters — see `docs/velocity-plugin.md`. A missing artefact is
refused loudly when a proxy is created, rather than producing a proxy with no
control endpoint.

---

## Dashboard API

Named here for completeness; `api/API.md` §12 is the full treatment, including
the reasoning behind the cookie defaults.

| Variable | Purpose |
|---|---|
| `MCORCH_API_LISTEN` | `host:port`, or `off` to run without the API at all |
| `MCORCH_API_TOKEN` | The operator credential. **No default**, minimum 32 characters |
| `MCORCH_API_ALLOWED_ORIGINS` | Exact origins, comma-separated. Never a wildcard |
| `MCORCH_API_SESSION_TTL` | How long an operator session lasts |
| `MCORCH_API_MAX_STREAMS` | Concurrent event streams |
| `MCORCH_API_MAX_BODY_BYTES` | Request body cap |
| `MCORCH_API_COOKIE_SECURE` | Defaults to false only on a loopback bind |
| `MCORCH_API_COOKIE_SAMESITE` | `Strict` by default; `None` requires a secure cookie |

There is no default token, and that is deliberate rather than an oversight: every
mutating endpoint can drain a Minecraft server, so an API that came up
unauthenticated because a variable was unset would be a data-loss bug. A
deployment either configures a credential or says `MCORCH_API_LISTEN=off` in so
many words.

The server speaks plain HTTP and binds loopback by default. Exposing it needs a
TLS terminator in front.

---

## Starting and stopping

**Shutting the orchestrator down does not stop anybody's server.** Containers keep
running and the next process to start reconciles them back into its view. A
Minecraft server only ever stops through the drain protocol, never because an
operator restarted the control plane.

A signal cancels the loop and waits for it to unwind before the node and the store
are closed. It does *not* wait for a pass in flight to finish — that would mean
waiting out a save timeout, minutes long, on every restart. What it does wait for
is the record of anything that pass already did, because the one side effect that
cannot be re-derived by observing the runtime is a save request.

Exit codes:

| Code | Meaning |
|---|---|
| `78` | Misconfigured. The log names the variable |
| `143` | `SIGTERM` — an ordinary stop |

Failing to bind the API port is fatal rather than degraded, for the same reason:
an orchestrator whose dashboard silently did not start looks exactly like a
healthy one until somebody needs it.

---

## A development containerd

`scripts/dev/containerd-up.sh` installs and starts an instance entirely separate
from any system containerd and from Docker Desktop's — its own socket, root,
state, config and CNI network, all namespaced under `mcorch-dev`. It reads and
writes nothing under `/run/containerd`, `/var/lib/containerd`, or any Docker
socket. Re-running it when the instance is already up reports and exits 0.

```bash
scripts/dev/containerd-up.sh      # socket: /run/mcorch-dev/containerd.sock
scripts/dev/containerd-down.sh
```

Both need `sudo`. Stopping containerd does not kill running containers — their
shims keep them alive.
