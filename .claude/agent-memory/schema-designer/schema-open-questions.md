---
name: schema-open-questions
description: Deliberately contentious schema calls awaiting a human ruling — including the live one, StorageStatus being documented as observed while derived from desired — and what was left out of PaperServer v1alpha1 on purpose
metadata:
  type: project
---

Raised with the human on 2026-07-26 when `PaperServer` v1alpha1 shipped. None had been ruled on yet
at that point — check whether the code still matches before repeating any of them.

**Why:** these are judgement calls where a reasonable person could go the other way, and two of them
(`latest`, `eulaAccepted`) make otherwise-working files fail to parse.

**How to apply:** if the human pushes back on one of these, it is a *change of decision*, not a bug
report — update the schema and this memory rather than patching around it.

Contentious, flagged for overrule:

- **`:latest` is rejected outright**, as is a reference with no tag or digest. Rationale: reconcile
  compares desired against observed, and a moving tag makes an image change unobservable, which can
  restart a server with players on it. Kubernetes only warns.
- **`spec.eulaAccepted` is required and must be `true`.** It is a legal declaration, not a system
  safety rule, and it makes the minimal example one line longer. Without it the container exits
  immediately at integration time.
- **`repo:tag@sha256:…` (both at once) is rejected**, though CI systems commonly emit it.
- **`resources.memory` is required** rather than defaulted. Defaulting a memory limit is magic; an
  operator should state how much memory a server may use.
- **`drain.policy` is an enum with exactly one value** (`waitForZeroPlayers`). It reads oddly but is
  an honest dispatch point for later policies, and it telegraphs that draining is an invariant.

Added 2026-08-04 with `VelocityProxy` (see [[schema-velocity-proxy-decisions]]), same status —
contentious, flagged for overrule:

- **A proxy cannot be given persistent storage at all.** No plugin-data volume, no config volume. The
  reason is strong (it is what keeps `holdsWorldData` a compile-time `false`, which is what keeps the
  proxy stoppable), but an operator wanting a LuckPerms cache to survive a restart has no answer.
- **The plugin's control-protocol version is not pinnable in the spec.** An operator who wants to run
  an older proxy image against a newer `:core` cannot express it; they get a `compatible: false`
  observation instead.
- **`spec.control.hostPort` forces `spec.control.tokenSecret`.** Defensible, but it means the
  simplest way to debug a control endpoint from outside the sandbox now requires a secret-store entry.
- **`spec.backends.fallback` names servers that must *also* match the selector**, and nothing checks
  that at parse time. A fallback that is not a backend parses and then never routes.
- **The proxy-side timings are on the proxy, not on the backend.** One slow backend cannot have a
  longer seal timeout than the rest of the fleet.

Raised 2026-08-08 and **awaiting a ruling** — the one live question, so check the code before
repeating it:

- **`StorageStatus` is documented as observed and is derived from the desired definition.**
  `Reconciler.Pass.storageStatus` fills `persistent` and `volumeName` from `definition.spec.storage`
  on every pass (converge and drain both), so a volume that does not exist, or exists under another
  name, reports exactly what was asked for. `bound` and `lastSaveConfirmedAt` on the same type *are*
  observed, which is why the type must not be renamed wholesale. Consequence on record as
  drain-audit item 149: a `persistent → ephemeral` edit landing in a window with no container
  (`Absent`, `SANDBOX_ONLY`, `CREATED`) is applied with no refusal, because the loop's only memory of
  "this server had a volume" is a field it rewrites from the edited definition. My recommendation is
  **make it observed** — `persistent` from the running container's `Labels.WORLD_DATA` (already in
  `WorkloadObservation.Present.labels`, no extra RPC), `volumeName` from the container's mounts
  (`ContainerStatus.mounts`, already fetched on containerd 2.3.3 but only when the sandbox overlay
  left `startedAt` null), the record carried forward rather than re-derived when nothing was
  observed, and no elvis back to the definition. Non-breaking if the producer simply stops writing
  what it did not see: `storage == null` already means "nothing observed". The rejected alternative —
  renaming the two derived fields — costs a wire break plus a store migration and closes nothing.
  Whoever takes this: it is a `:core` producer change plus a `:cri`→`Node` plumb, so it goes to
  `reconciler-dev` + `cri-integration-dev` as one unit and through `drain-auditor`, because the
  field it fixes sits next to the world-data decision.

Deliberately left out of v1alpha1 (do not assume these exist):

- No free-form `env` map and no `serverProperties` block — an `env` map is precisely how a
  forwarding secret ends up inline. `maxPlayers` is in the spec because the drain protocol needs a
  capacity notion; `motd`/`difficulty`/`gamemode` are not.
- No `running: false` / desired-phase field. Deleting the definition is the stop path for now;
  adding it later is additive.
- No `nodeSelector` (only an optional `placement.node` pin), no restart policy, no health-probe
  configuration, no cross-definition port or volume-name conflict checking (a single parse only sees
  one input).
- ~~The proxy-side drain handshake timeouts (seal, destination lookup, deregister) are reconciler
  constants, not spec fields.~~ **Reversed 2026-08-04**: they are now `spec.backends.drain` on the
  `VelocityProxy`. The original reasoning ("they do not vary per server") still holds and is exactly
  why they sit on the proxy rather than on each backend.
