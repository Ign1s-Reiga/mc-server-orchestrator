# Writing server definitions

A server definition is a YAML document describing one server you want to exist.
You declare what you want; the reconcile loop makes it so and reports back. You
never write status, and you never edit a container.

Two kinds exist today:

| `kind` | What it is |
|---|---|
| `PaperServer` | One Paper Minecraft server. May hold a world. |
| `VelocityProxy` | The front door players connect to, and the control channel the backend drain protocol talks to. Never holds a world. |

Both use `apiVersion: mcorch.dev/v1alpha1`, which is the only accepted value.

---

## The shortest thing that works

A `PaperServer`:

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

A `VelocityProxy`:

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
    secret:
      name: velocity-forwarding
      key: secret
  backends:
    selector:
      matchLabels:
        fleet: survival
```

Everything else has a default. The defaults are on the safe side: storage is
persistent, drains wait for zero players, and the control endpoint is not
published.

---

## The envelope

Every document has the same four top-level keys.

| Field | Required | Notes |
|---|---|---|
| `apiVersion` | yes | `mcorch.dev/v1alpha1` |
| `kind` | yes | `PaperServer` or `VelocityProxy` |
| `metadata.name` | yes | lowercase RFC 1123 label, ≤ 63 chars. Unique across the fleet. |
| `metadata.labels` | no | string→string map, default `{}`. This is how a proxy finds its backends. |
| `spec` | yes | kind-specific, below |

`metadata.generation` is assigned by the store and cannot be written. A file may
hold several documents separated by `---`, in which case names must be unique
within it — but the HTTP API takes **one document per request**.

---

## `PaperServer`

### Required

| Path | Type | Notes |
|---|---|---|
| `spec.image` | image ref | Must be pinned to a tag or digest. `latest` is rejected. |
| `spec.paper.minecraftVersion` | string | `"1.21.8"` — quote it, or YAML reads it as a number. Snapshots are not expressible. |
| `spec.eulaAccepted` | bool | Must be `true`. |
| `spec.resources.memory` | memory | 1Gi … 1Ti |

### Optional

| Path | Default | Range |
|---|---|---|
| `spec.paper.build` | image chooses | ≥ 1 |
| `spec.maxPlayers` | `20` | 1 … 10000 |
| `spec.resources.cpu` | unlimited | ≤ 256 cores |
| `spec.resources.heap.max` | derived from `memory` | ≥ 256Mi |
| `spec.resources.heap.min` | same as `heap.max` | ≥ 256Mi, ≤ `heap.max` |
| `spec.network.port` | `25565` | 1 … 65535 |
| `spec.network.hostPort` | unpublished | 1 … 65535 |
| `spec.network.rcon.enabled` | `false` | |
| `spec.network.rcon.port` | `25575` | must differ from `network.port` |
| `spec.network.rcon.passwordSecret` | — | **required** when `rcon.enabled: true` |
| `spec.storage.mode` | `persistent` | `persistent` or `ephemeral` |
| `spec.storage.mountPath` | `/data` | absolute, not a system path |
| `spec.storage.volume.name` | `metadata.name` | persistent only |
| `spec.storage.volume.size` | unset | ≥ 1Gi |
| `spec.lifecycle.drain.policy` | `waitForZeroPlayers` | only value |
| `spec.lifecycle.drain.playerTransferTimeout` | `120s` | 1s … 1h |
| `spec.lifecycle.drain.saveTimeout` | `180s` | 1s … 1h |
| `spec.lifecycle.stopGracePeriod` | `saveTimeout + 60s` | 1s … 2h, and see the margin rule |
| `spec.lifecycle.startupTimeout` | `5m` | 1s … 1h |
| `spec.placement.node` | scheduler chooses | |

### The heap default is not the memory limit

If you do not set `spec.resources.heap.max`, it is derived by leaving headroom
below the container limit — 20% clamped to between 512Mi and 2Gi. So `memory:
4Gi` gives a **3276Mi** heap, and `memory: 1Gi` gives 512Mi.

Setting the heap *to* the container limit is rejected, not clamped:

> must leave headroom below the container memory limit: with
> `spec.resources.memory=4Gi` the largest heap is 3276Mi, found 4Gi. A heap sized
> at the container limit is OOM-killed mid-tick with the world unsaved

That is the whole reason the rule exists. The JVM is not the only thing in the
container, and the process that gets killed for the difference is the one holding
your world open.

### The grace period must outlast the save

`spec.lifecycle.stopGracePeriod` must exceed `spec.lifecycle.drain.saveTimeout`
by at least 30 seconds. The default keeps a 60-second margin for you; if you set
either by hand, keep the relation:

> must exceed `spec.lifecycle.drain.saveTimeout` (3m) by at least 30s, so at
> least 3m30s, found 1m. A grace period shorter than the save timeout kills the
> container part-way through the save

The two are a validated **pair**. Nothing in the system will shorten one without
the other, and that is deliberate — see [operating.md](operating.md) note 3.

### RCON does nothing until you enable it

`spec.network.rcon.port` and `passwordSecret` are read and shape-checked even
when `enabled` is absent or `false`, and then discarded. There is no warning. If
RCON is not working, check `enabled: true` first.

Enabling it is not optional if you care about deleting the server later. **A
persistent server with RCON disabled cannot be drained**, because the world save
cannot be confirmed, so `DELETE` never completes. That behaviour is correct and
is explained in [operating.md](operating.md) note 1 — but the time to notice it
is while you are writing the definition.

### Ephemeral storage is opt-in and means what it says

`storage.mode: ephemeral` gives the container no volume that outlives it. Use it
for lobbies and minigame instances that generate their world on start. An
ephemeral server still drains its players; it just has nothing to save.

Declaring a `volume` alongside it is an error rather than an ignored field.

---

## `VelocityProxy`

### Required

| Path | Type | Notes |
|---|---|---|
| `spec.image` | image ref | pinned, as above |
| `spec.resources.memory` | memory | 1Gi … **64Gi** |
| `spec.forwarding.secret` | secret ref | coordinates only |
| `spec.backends.selector.matchLabels` | map | non-empty, ≤ 16 entries |

### Optional

| Path | Default | Range |
|---|---|---|
| `spec.resources.cpu` | unlimited | ≤ 64 cores |
| `spec.resources.heap.max` / `.min` | derived, as Paper | ≥ 256Mi |
| `spec.forwarding.mode` | `modern` | only value |
| `spec.backends.fallback` | `[]` — any backend, by name | ≤ 16, no duplicates |
| `spec.backends.drain.sealTimeout` | `10s` | 1s … 1h |
| `spec.backends.drain.destinationTimeout` | `30s` | 1s … 1h |
| `spec.backends.drain.deregisterTimeout` | `10s` | 1s … 1h |
| `spec.control.port` | `8375` | must differ from `network.port` |
| `spec.control.hostPort` | unpublished | must differ from `network.hostPort` |
| `spec.control.tokenSecret` | — | **required** when `control.hostPort` is set |
| `spec.maxPlayers` | `500` | 1 … 100000 |
| `spec.network.port` | `25577` | **must be exactly 25577** |
| `spec.network.hostPort` | unpublished | this is the port players type |
| `spec.lifecycle.drain.sealTimeout` | `10s` | 1s … 1h |
| `spec.lifecycle.stopGracePeriod` | `60s` | 1s … **1h** |
| `spec.lifecycle.startupTimeout` | `2m` | 1s … 1h |

A proxy has no `spec.storage`, no `spec.paper`, no `spec.eulaAccepted` and no
RCON. Writing `spec.storage` on one is an unknown-field error, and that absence
is load-bearing rather than an oversight — a proxy holds nothing worth keeping.

### `network.port` is not the port you choose

On a proxy, `spec.network.port` is a claim about where Velocity listens inside
its sandbox, and 25577 is the only legal value. **To choose the port players
connect to, set `spec.network.hostPort`.**

Getting this wrong on a *running* proxy is worse than a rejected file: it changes
the container's shape, which starts a replacement drain that then cannot finish.

### There are two `sealTimeout`s and they are not the same

- `spec.backends.drain.sealTimeout` — this proxy's part of a **backend's** drain.
- `spec.lifecycle.drain.sealTimeout` — the **proxy's own** drain.

Both default to `10s`. They are read on different paths and tuning one does not
affect the other.

### Publishing the control endpoint requires a token

`spec.control.hostPort` is unset by default, which keeps the control endpoint on
the sandbox network where only the orchestrator reaches it. That is the safe
state. If you publish it, a token becomes mandatory:

> is required when `spec.control.hostPort` is set: a control endpoint published
> off the sandbox can seal backends and move every player in the fleet, so it is
> authenticated. The token is named in the secret store, it is never written in a
> definition

---

## Secrets are always references

Three fields take a secret, and all three take **coordinates into the secret
store**, never a value:

```yaml
passwordSecret:
  name: survival-01-rcon   # lowercase RFC 1123 label, <= 63
  key: password            # <= 253
```

They are `spec.network.rcon.passwordSecret` on a Paper server, and
`spec.forwarding.secret` and `spec.control.tokenSecret` on a proxy. Both
sub-keys are required.

**Nothing may be inline, anywhere, on any kind.** Two separate guards enforce it:
writing a scalar where a ref belongs, and writing *any* unrecognised key whose
name looks secret-like — `password`, `token`, `secret`, `forwardingSecret`,
`apiKey`, `credentials` and similar, at any depth. Both produce:

> inline secrets are not supported anywhere in a definition. Put the value in the
> secret store and reference it by coordinates — `{name: <secret name>, key: <key
> within it>}` — the way `network.rcon.passwordSecret` and `forwarding.secret` do

The message deliberately never echoes the value you wrote, so a secret pasted
into a definition does not then get copied into a log or an API response. It is
still burned: rotate it.

The forwarding secret in particular is only ever handed to backends by the
reconciler. A `PaperServer` never names it, and never names the proxy.

---

## Scalar formats

| Kind | Written as | Notes |
|---|---|---|
| Duration | `30s`, `5m`, `1m30s`, `250ms` | units `ms`, `s`, `m`, `h`. ISO-8601 (`PT5M`) is **not** accepted. Every duration field has a 1s minimum, so `250ms` parses and is then out of range. |
| Memory | `4Gi`, `512Mi`, `2G`, `1073741824` | binary `Ki/Mi/Gi/Ti` and decimal `K/M/G/T`. Fractions truncate down. |
| CPU | `2`, `1.5`, `500m` | must be greater than zero |
| Name | `survival-01` | lowercase RFC 1123 label, ≤ 63 |
| Node name | `node-a.example.com` | DNS-style, dots allowed, ≤ 253 |
| Label | `fleet: survival` | key and value ≤ 63; empty value allowed |

Booleans must be lowercase `true` / `false`. YAML 1.1 spellings are rejected with
a message that says why, because this is a genuine trap:

> expected a boolean `true` or `false`; `yes` is a plain string in YAML 1.2, not
> a boolean

---

## When a definition is wrong

Parsing does not stop at the first problem. One pass reports **every** violation,
each with a field path, an explanation, and a `line:column` into your file:

```
spec.resources.heap.max:1:  must leave headroom below the container memory limit: …
spec.lifecycle.stopGracePeriod:  must exceed spec.lifecycle.drain.saveTimeout (3m) by …
```

Unknown fields are rejected rather than ignored, with a spelling suggestion:

> unknown field. did you mean `storage`? known fields here: `image`, `paper`, …

An explicitly-null field is also an error, because `storage:` with nothing under
it usually means someone deleted a block and meant to delete the key:

> must not be null; omit the field entirely to use its default

Validation happens entirely at parse time. An invalid definition never reaches
the reconcile loop, so there is no state in which a bad definition is "accepted
but failing".

---

## Values that get bounded rather than rejected

Some durations have a ceiling this build will act on: the Paper save timeout
(1h), the Paper stop grace period (2h), the proxy stop grace period (1h), and
each of the three backend handshake timeouts (1h each).

**You will never hit this by submitting YAML.** Every ceiling equals the widest
value a reader already accepts, so a definition that parsed cannot be clamped.
The bound exists for definitions that did not come through a reader — a stored
row from an older build, a hand-edited database, a migration. When it fires, the
stored document is left untouched and a line appears in the log:

> `server={} field={} declares {} above the {} this build will act on; using the
> ceiling. The stored value is unchanged — edit the definition to clear this`

There are ceilings but no floors, on purpose: a floor under the save timeout
would invert the grace-period pair described above.

---

## Status is not something you write

There is no reader for status types. The reconcile loop writes them and the API
serves them; a definition that contains a `status:` block gets an unknown-field
error.

What you will see reported back:

- `phase` — `PENDING`, `IMAGE_PULLING`, `CREATING`, `STARTING`, `RUNNING`,
  `DRAINING`, `STOPPING`, `STOPPED`, `FAILED`, `UNKNOWN`
- `conditions[]` — `IMAGE_AVAILABLE`, `VOLUME_BOUND`, `CONTAINER_RUNNING`,
  `READY`, `DRAINING`, `DRAIN_BLOCKED`, `PLAYERS_EVACUATED`, `WORLD_SAVED`,
  `BACKENDS_RESOLVED`, `CONTROL_ENDPOINT_READY`, `NEEDS_ATTENTION`
- `failure` — `{reason, failureClass, message, occurredAt, attempts}`, where
  `failureClass` is `RETRYABLE` or `PERMANENT`
- `drain` — the drain state machine's own record
- `observedGeneration` — which version of your definition the loop has acted on

Occupancy is reported as a **count**. Player names, UUIDs and addresses are never
stored, logged or served.

When something is stuck, `docs/operating.md` covers the cases that are deliberate
rather than broken, and what to do about each.

---

## Versions

`apiVersion` is mandatory and today has exactly one value. `v1alpha1` means
fields may still be removed or retyped.

When that happens, the old version keeps parsing: a new version gets its own
reader, an old document is converted and re-emitted at the current version with a
log line, and nothing downstream ever sees the old shape. Versions are dispatched
per *(version, kind)* pair, so the two kinds can move independently.

A stored row this build cannot decode does not fail the whole read — it comes
back marked unreadable, alone, and the rest of the fleet is unaffected.

---

## Worked examples in the repo

Under `schema/src/testFixtures/resources/examples/`. These are the definitions
the test suite parses, so they cannot drift from the code.

**`valid/`**

| File | Shows |
|---|---|
| `minimal.yaml` | the smallest `PaperServer` that parses |
| `full.yaml` | every `PaperServer` field written out, including a digest pin and an explicit volume |
| `ephemeral-lobby.yaml` | `storage.mode: ephemeral` |
| `multi-document.yaml` | several definitions in one file |
| `proxy-minimal.yaml` | the smallest `VelocityProxy` |
| `proxy-full.yaml` | every `VelocityProxy` field written out |

**`invalid/`** — 29 files, each headed by a comment naming the violation it is
there to produce. Worth reading before writing your first definition: they are
the mistakes people actually make, including `inline-secret.yaml`,
`grace-below-save-timeout.yaml`, `heap-exceeds-memory.yaml`,
`proxy-published-control-without-token.yaml`, and `many-problems.yaml`, which
produces seven violations from one parse.
