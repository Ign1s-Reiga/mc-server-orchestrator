---
name: schema-open-questions
description: Deliberately contentious schema calls awaiting a human ruling, and what was left out of PaperServer v1alpha1 on purpose
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
