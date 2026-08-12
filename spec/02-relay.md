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

### 4.1 The address path already exists

An earlier draft of this document said the sandbox IP was not surfaced and would
have to be plumbed through `:cri`. **That was wrong.** `SandboxStatus` already
carries `ips: List<String>`, primary first, redacted from its own `toString`;
and `Node.callEndpoint` already resolves that address and dials it, to reach the
Velocity control plugin for drain steps 2, 4 and 6.

So there is no `:cri` work here, and the relay is not inventing a pattern —
`callEndpoint` **is** the pattern, and its KDoc states the reasoning the console
channel should inherit verbatim:

> A remote node would forward this to its own agent and never expose an address
> at all; on this host the sandbox has a CNI address and the orchestrator process
> can open a socket to it directly. That difference is the whole reason
> `Node.callEndpoint` exists as an interface method — a caller that resolved an
> address itself would have hard-coded the single-host deployment.

Three properties the console channel takes from it:

- **It is an interface method on `Node`.** A caller that resolved an address
  itself would hard-code single-host, which the seventh invariant exists to
  prevent.
- **The address is read fresh on every call, never cached on the handle.** A
  recreated sandbox gets a new address, and a request aimed at the previous
  occupant of one is a request sent to somebody else's server. `callEndpoint`
  makes this point about sealing the wrong backend; a console command sent to the
  wrong server is the same error with a worse blast radius.
- **The address is never logged.** Not in a failure message, not at debug.
  Failures name the port, which is declared configuration.

A sandbox with no address yet is a **wait, not a misconfiguration** —
`callEndpoint` raises a retryable `NodeException.Busy` carrying
`NodeDispatch.NOTHING_SENT`. The console channel needs the same distinction, and
[07-api.md](07-api.md) maps it to `CONSOLE_UNAVAILABLE`, which is retryable and
safe to retry precisely because nothing was sent.

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

### 6.1 Authority concentration

A per-node relay can reach the console of every server on its node, so
compromising it yields console authority over all of them.

What it does **not** need to hold is the passwords. `LocalNode.resolveToken`
already establishes the pattern for the Velocity control token — *"Coordinates
in, material out, and the material never leaves this function"* — resolving the
secret, using it, and destroying it in a `finally`.

RCON authenticates once per connection, so a relay built on persistent
connections needs the password only at connect time. Resolve it there, send the
auth packet, destroy it. **The relay then holds N authenticated sockets, not N
credentials**, and a memory disclosure yields sessions rather than secrets.

This does not remove the authority concentration in the first paragraph, which is
inherent to the shape. It removes the credential concentration, which is not.

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

A host-side relay resolves that material into the orchestrator's memory, which
`rcon-cli` in-container never required. The auth handshake also moves from
loopback onto the CNI bridge.

Both are real. Both are narrower than an earlier draft of this document claimed:
per §6.1 the material is held only for the length of a connect, and the CNI
bridge is host-local rather than an external interface. The Velocity control
token already crosses the same boundary on the same terms, so this is an existing
trade being extended rather than a new one being made.

Accepted in exchange for not draining the fleet (§5).
