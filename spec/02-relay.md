# 02 — The per-node relay

## 1. Shape

```
Dashboard (separate repo)
      │  HTTPS + session cookie
      ▼
:api   console route ── policy gate ──► audit sink
      │                                (unconditional redaction)
      │  new :core edge — see 01-impact.md §2
      ▼
:core  Node handle ──► per-node relay ──► one RCON connection per server
      │                                    dialed at the sandbox CNI IP
      │
      └─ drain: Node.exec → ExecSync → rcon-cli   ◄── deliberately NOT via relay
```

Two independent channels reach the same RCON port. That redundancy is the point.

## 2. Why host-side, dialing the sandbox IP

`SandboxSpec` defaults the network namespace to `POD`, and `containerd-up.sh`
installs and configures CNI. Every sandbox therefore gets its own netns with a
CNI-assigned IP that is **routable from the host**.

This is what makes a host-side relay viable without publishing anything.
`PaperWorkload.kt:53` is explicit about why the RCON port stays unpublished:

> Deliberately not published on the host: RCON is a remote console with full
> server authority, and the only thing that needs it is an exec from inside the
> sandbox.

Dialing the sandbox IP honours that. Publishing the port on the host would not,
and would additionally put RCON's cleartext auth packet on an external interface.

## 3. Why one per node

"Per node" is already the seam this codebase is built around. A relay per node:

- maps onto `Node`, so every call site addresses it through the handle rather
  than assuming locality — which is what the seventh invariant requires;
- exploits the only concurrency dimension that actually exists (see
  [05-concurrency.md](05-concurrency.md)): N servers are N independent main
  threads;
- distributes with `Node` when nodes multiply, with no new addressing scheme.

A single fleet-wide relay would need cross-node network reach and would become a
second, competing notion of "where a server is".

## 4. Packaging: a component, not a deployable

Build it as a component behind the `Node` interface — today a component in the
orchestrator's JVM dialing sandbox IPs.

This gives the architecture without a new artifact to deploy, supervise, version
and secure. When nodes multiply it moves with `Node`, and every caller is already
written against the handle.

### 4.1 What `:cri` must surface

`SandboxStatus` today carries id, labels and `createdAt`. It does **not** carry
the sandbox IP. That must be plumbed through `:cri` and surfaced on the `Node`
handle.

Two constraints on doing so:

- **The relay receives a reachable address from the `Node` handle. It never
  derives or guesses one.** An address the relay works out for itself is a "the
  local one" assumption, and the seventh invariant exists to keep those out.
- **The address is a routing detail, never a log field.** `SandboxSpec` already
  redacts host IPs and nameserver addresses from `toString` on the grounds that
  structured logging must not carry IP addresses. A sandbox IP on `Node` inherits
  that discipline.

## 5. Why not a sidecar

The sidecar version — a relay container inside each sandbox — is *better* on
secret handling. It would read `RCON_PASSWORD` from container env exactly as
`rcon-cli` does, so `BringUpTest`'s assertion that *the RCON password travels as
a reference, never as a value* would keep holding, and the auth handshake would
never leave loopback.

It is rejected because adding a container changes the workload shape, which lands
in the spec hash, which means a recreate — and a recreate of a persistent server
is a drain. **Every existing server would pay one.** On the fleet sizes Forge
support implies, that one-time cost is the larger of the two.

The trade was made knowingly. The cost it bought is §6.3 below, and it is
permanent.

## 6. Costs accepted explicitly

### 6.1 Secret concentration

A per-node relay holds RCON passwords for every server on its node. Compromising
it yields console authority over that whole node.

Mitigation bounds the window rather than removing it: resolve a server's password
lazily on first use and drop it after idle, rather than loading the node's full
set at startup.

### 6.2 A new failure domain

The relay can die, wedge, or hold a stale connection, and `:core` must model that.
When it does, the console is unavailable — and *only* the console.

This is guaranteed by the drain keeping its own channel. `PaperServerAgent.saveAll()`
continues to use `Node.exec` → `ExecSync` → `rcon-cli`, unchanged and unaware of
the relay. Route the drain through the relay and a relay outage becomes a
data-safety outage; the two channels are deliberate redundancy, and the
safety-critical one keeps the dumber, more robust path.

### 6.3 The reference-not-value property

`PaperWorkload` puts `RCON_PASSWORD` into the container's `secretEnv`, and
`rcon-cli` reads it in-container. That is why `BringUpTest` can assert *the RCON
password travels as a reference, never as a value*.

A host-side relay resolves that material into its own memory, making the
assertion false in a new place, permanently. The auth handshake also moves from
loopback onto the CNI bridge.

Accepted in exchange for not draining the fleet (§5).
