---
name: schema-velocity-proxy-decisions
description: Why VelocityProxy v1alpha1 is shaped the way it is — backends by selector, the forwarding secret's single coordinate, no storage block, the control endpoint, and the DRAIN_NO_DESTINATION split
metadata:
  type: project
---

The second kind (`VelocityProxy`, `mcorch.dev/v1alpha1`) landed 2026-08-04 on `feat/velocity-proxy-kind`.
It is the first spec that refers to *other* servers and the first that has a secret at all, so several
calls here have no precedent in [[schema-v1alpha1-decisions]].

**Why:** these are the decisions that would get re-litigated by whoever adds the third kind or
implements the proxy side of the drain protocol, and most of them are arguments rather than facts.

**How to apply:** follow these unless the human overrules; the ones flagged for overrule are in
[[schema-open-questions]].

- **The forwarding secret has exactly one coordinate in the system**, `spec.forwarding.secret` on the
  proxy. Modern forwarding needs the same value on every backend, and the backend gets it because it
  *matches the proxy's selector* — the reconciler resolves the proxy's `SecretRef` and injects it at
  container-create time. `PaperServerSpec` therefore stays unchanged and never names a proxy. Neither
  side carries material; the reference direction is proxy → backend and only ever that way.
- **A proxy has no `storage` block and cannot be given one.** `ServerSpec.holdsWorldData` is a
  constant `false` for it. This is not tidiness: `contractOf` defaults `holdsWorldData` to `true` (the
  safe side), Velocity has no RCON so `saveConfirmable` is false, so an unconfirmable save →
  `PERMANENT` → a container the orchestrator could never stop. Adding optional plugin-data persistence
  later would reopen that, which is the reason not to.
- **`holdsWorldData` was hoisted onto the sealed `ServerSpec` interface** so the compiler asks every
  future kind. It was a workload-builder convention and nothing would have caught a kind that forgot.
  `:core` still records it on the workload's labels at create time and reads it back from there — a
  drain is conducted against the container, not against the definition as it reads today.
- **The plugin's control-protocol version is not in the spec.** The plugin ships from this repo at
  `:core`'s version, so which protocol they speak is a property of the binary pair. A spec field could
  only ever be set to something `:core` cannot speak, and would go stale in every stored definition on
  every release. It is observed instead: `ControlEndpointStatus.pluginApiVersion` + `compatible`,
  `ConditionType.CONTROL_ENDPOINT_READY`, `FailureReason.PROXY_PLUGIN_INCOMPATIBLE`.
- **The control endpoint's safe default is "unpublished, no token".** Setting `control.hostPort` makes
  `control.tokenSecret` required at parse time, because a published control plane can seal every
  backend and move every player. Omission stays safe either way and the unsafe combination is
  unspellable.
- **Proxy-side drain timings (seal / destination / deregister) moved from reconciler constants to
  `spec.backends.drain`** — on the proxy, not the backend, because they measure how long *this proxy*
  takes to answer. The transfer timeout stayed on the backend: it scales with that server's players.
- **An empty `matchLabels` is a violation** (parser + `BackendSelector.init`); a selector that matches
  *nothing* is an observation (`BackendRoutingStatus`, `ConditionType.BACKENDS_RESOLVED`), because one
  parse only sees one document. Same reason there is no cross-document check that two proxies do not
  claim the same backend — that is a reconciler/admission rule.
- **`FailureReason.DRAIN_NO_DESTINATION` was split.** It now means only "the search ran and the fleet
  had no capacity", which needs a human and therefore *does* escalate. The new
  `DRAIN_AWAITING_ZERO_PLAYERS` means "no transfer counterparty exists, waiting for people to log
  off", and it is the only reason exempt from `NEEDS_ATTENTION`. `FailureStatus.ALWAYS_RETRYABLE` is
  one `require` over a set rather than one per reason: every check there is paid by the widest read in
  the system, so a second one is a second way for a fleet read to abort.
- **`PaperServerStatus.drainInitiated` (`drain != null`) exists beside `draining`
  (`drain != null && state != DRAIN_FAILED`)**, and destination eligibility must use the former.
  `BackendStatus.eligibleAsDestination` bundles the whole rule so no caller re-derives half of it.
- **`ControlEndpointStatus.credential` is a three-valued enum, not an `authenticated: Boolean`**
  (added 2026-08-08, `feat/control-credential-status`). Reachable ≠ usable: `GET /v1/version` needs no
  token by design, so `reachable`/`compatible` stay true on a proxy that 401s every seal, transfer and
  deregistration — the state a secret rotation produces, because the spec hash carries the token's
  *coordinates* and not its value. A boolean cannot express "no authenticated call was made this
  pass", and either default invents an observation on every pre-existing row: `true` is a green lamp
  nobody lit, `false` is a fleet-wide credential alarm at the instant of an upgrade. `UNTESTED` is the
  default and means *no evidence*. One derived `usable` (`reachable && compatible && credential !=
  REJECTED`) lives on the type so `:core`'s condition and `:api`'s badge cannot drift; it treats
  `UNTESTED` as *not refused* rather than *not accepted*, so it can only go false where something was
  observed to fail. Populated from calls the pass already makes — no wire change, no extra round trip.
  `usable` is a **presentation predicate**, not a gate: its leniency is licensed by the narrowness of
  its consumer list (the condition and `:api`'s renderers), which a scan enforces, and any gate must
  require `ACCEPTED`. `ControlCredential.refinedBy` holds the merge rule — `UNTESTED` never overwrites
  evidence — in `:schema` because the routing sweep and the proxy drain both apply it.
- **A three-valued status field needs a *seed* rule and a *retirement* rule, and they live at
  different sites** (round 44). Seed: the fact is carried into every record the loop builds, because
  a pass that establishes nothing must not erase what an earlier pass established — a fresh record
  each pass turned one broken thing (the player port) into the clearing of an unrelated alarm.
  Retirement: the fact dies where the **container** it describes dies, which for a proxy is
  `convergeProxy`'s two create branches, beside the drain record's clear and for the same stated
  reason. What does *not* work is gating the seed on container identity at the read site: the create
  pass writes the new id into `runtime` first, so the comparison always finds them equal. That gate
  was written, was dead, and only a mutation found it — the test covering it was measuring the sweep
  that re-establishes the verdict. Whenever a status field is *about the process rather than the
  definition*, ask both questions separately and write the answers at the two sites.
- **The retirement rule must be a *positive* identity test, because "no recorded container id" is not
  silence** (round 45). Written as "clear when both ids are known and differ", it walked past a
  partial removal: `teardownProxy` nulls `runtime.containerId` deliberately, to record that the loop
  took the container away and the sandbox survived — so the state that says loudest *the old
  container is gone* was the one state the guard treated as "no evidence of a new container". Keep on
  confirmed identity (`observed != null && observed == recorded`) and neither absence can be read as
  evidence. Generally: before writing `x == null` into a guard, find every writer of `x` and ask
  whether any of them nulls it as a *statement* rather than for want of an answer.
- **Deliberately left out of the proxy:** any Velocity/Minecraft version field (the image is already
  pinned and a proxy speaks all protocols — unlike Paper, whose launcher image downloads a build per
  version), forced hosts, motd, compression, a plugin list (the reconciler mounts the shipped plugin),
  any timeout on waiting for the proxy's own players to leave (the only way to spell one is
  "disconnect them"), and any cross-field rule tying `stopGracePeriod` to anything — a proxy has no
  save, and inventing an invariant would teach the wrong lesson about which grace periods matter.
