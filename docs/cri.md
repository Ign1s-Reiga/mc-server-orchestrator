# The CRI client

`:cri` is the only place this project talks to a container runtime. Everything
above it — the reconcile loop, the drain, the node abstraction — reaches
containerd through the interface described here and never sees a gRPC, protobuf
or netty type.

That containment is enforced by the build rather than by convention: every
dependency in `cri/build.gradle.kts` is `implementation`, never `api`, so a
`io.grpc.*` type cannot appear on `:core`'s compile classpath. After changing
anything here, prove it from the consumer's side:

```bash
./gradlew :core:dependencies --configuration compileClasspath
```

For where the `.proto` comes from and how the stubs are generated, see
[`cri/PROTO_SOURCE.md`](../cri/PROTO_SOURCE.md) and the `generate-cri-stubs`
skill. This document is about the wrapper written over them.

---

## What crossing this boundary costs

Two properties are required of everything here, and they shape the whole surface:
CRI calls are **failable** and **slow**. Every operation therefore has a deadline
and is cancellable, and every failure arrives classified rather than raw.

### Deadlines

`CriTimeouts` gives each class of RPC its own budget:

| Timeout | Default | Covers |
|---|---|---|
| `query` | 15s | version, status, container/sandbox status, listings |
| `sandboxLifecycle` | 2m | run, stop, remove a sandbox |
| `containerLifecycle` | 2m | create, start, remove a container |
| `imagePull` | 30m | `PullImage` — a first pull of a server image is minutes |
| `imageLifecycle` | 2m | image status, listing, removal |
| `deadlineSlack` | 30s | added on top of a caller-supplied wait |
| `stopDeadlineCap` | 2h | the most of a stop grace period that may become a deadline |

`deadlineSlack` is a margin, not a bound. A `stopContainer` with a 120s grace
period gets a 120s + 30s transport deadline, so the runtime's own kill fires
*before* the transport gives up and the caller learns the container actually
stopped rather than receiving an ambiguous timeout. The same applies to
`execSync`'s command timeout.

### `stopDeadlineCap`, and what it does not do

The deadline for a stop is `min(gracePeriod, stopDeadlineCap) + deadlineSlack`.

**The grace period containerd is asked for is never shortened by this.** The whole
value goes on the wire, so nothing here can make a container be killed sooner
than the caller asked. What the cap bounds is the other thing a grace period used
to decide: how long a single RPC may park a reconcile worker. Without it, a
definition carrying a 30-day grace period parks a worker for a month with no
effective deadline — which violates the rule that everything crossing this
boundary has a timeout.

When the cap does bite, the call ends while containerd is still waiting, and the
consequence is that the container keeps running **longer**, never shorter. That
claim rests on containerd's own behaviour rather than on this module's belief:
`internal/cri/server/container_stop.go` waits out the grace period on a context
derived from the request's, and between that wait and the `SIGKILL` sits
`if ctx.Err() != nil { return ctx.Err() }`. A client that gave up first means the
kill is never reached. `StopDeadlineCapIT` pins this against a real runtime,
because a fake server would simply agree with whatever this module believed.

Note the asymmetry in the KDoc, because it is easy to get backwards: **raising
the cap is a local decision, lowering it is not.** `:core` reasons about the point
at which a healthy stop starts returning a retryable timeout, and that reasoning
rests on this default.

### `StopGracePeriod`

A value class rather than a `Duration`, because the wire field is whole seconds
and the conversion has an edge that bit once. `MAX_SECONDS` is `9_223_372_036` —
above it, a nanosecond-based `Duration` overflows on the way in. Construction
returns a `Result` and refuses anything larger with a message naming the bound.
`IMMEDIATE_KILL` is the explicit zero.

---

## Failures arrive classified

`CriException` is a sealed hierarchy, one subclass per meaning, each carrying the
`CriOperation` that failed and a `CriStatusCode` re-declared locally so callers
can refine a decision without depending on `io.grpc`.

**Retryable** — the loop backs off and comes back:

| Exception | From | Meaning |
|---|---|---|
| `Unavailable` | `UNAVAILABLE` | the runtime is not answering |
| `Timeout` | `DEADLINE_EXCEEDED` | a deadline elapsed — see below |
| `ResourceExhausted` | `RESOURCE_EXHAUSTED` | |
| `Aborted` | `ABORTED` | |
| `RuntimeFailure` | `UNKNOWN`, `INTERNAL`, `DATA_LOSS` | containerd failed without classifying it |

**Not retryable** — the loop surfaces it on observed status and stops:

| Exception | From | Meaning |
|---|---|---|
| `NotFound` | `NOT_FOUND` | unknown ID, or an image not in the registry |
| `AlreadyExists` | `ALREADY_EXISTS` | the object is already there |
| `InvalidArgument` | `INVALID_ARGUMENT`, `OUT_OF_RANGE` | the request as written is rejected |
| `FailedPrecondition` | `FAILED_PRECONDITION` | wrong state for this call |
| `PermissionDenied` | `PERMISSION_DENIED`, `UNAUTHENTICATED` | containerd authz, or socket file permissions |
| `Unimplemented` | `UNIMPLEMENTED` | a CRI/containerd version mismatch |
| `Cancelled` | `CANCELLED` | containerd cancelled from its side |

`RuntimeFailure` is the one judgement call. containerd reports genuinely transient
conditions — snapshotter contention, a registry hiccup mid-pull — as `UNKNOWN` or
`INTERNAL`, and a bounded backoff recovers from those, so it is retryable on
purpose and has its own subclass to say so.

`Timeout.commandTimeout` distinguishes two things a single status code conflates:
`true` means the runtime stopped a caller-supplied timeout (only `EXEC_SYNC` can
set it) and **says nothing about the node's health**; `false` means this client's
transport deadline elapsed. A drain that reads a timed-out probe as "the server
is empty" is a data-loss bug, so the difference is carried explicitly.

### What callers do with them

The mapping to a `FailureReason` an operator sees is `:core`'s, not this
module's — see `docs/server-lifecycle.md`. Two cases are worth knowing because
they are not one-to-one:

- **A name collision is two status codes.** containerd 2.3.3 rejects a duplicate
  at *name reservation*, and a reservation conflict is `FAILED_PRECONDITION`, not
  `ALREADY_EXISTS`. `LocalNode` matches both, and does so at the call site rather
  than in this module's code mapping, because `FAILED_PRECONDITION` legitimately
  covers other things and only a caller that has just attempted a create knows a
  precondition failure means the name is taken.
- **`NotFound` is often not a failure at all.** Observing a workload that is gone
  is the expected answer during teardown.

---

## Error text and secrets

A runtime's error message can quote the request that failed, and some requests
carry secret material. This module resolves that rather than handing the problem
to callers.

`CriOperation.requestMayCarrySecrets` is true for exactly three operations:

- `PULL_IMAGE` — carries `AuthConfig`, whose `password`, `auth`, `identity_token`
  and `registry_token` the CRI proto itself marks `debug_redact = true`.
- `CREATE_CONTAINER` — carries `ContainerConfig.envs`, which is where the RCON
  password goes and the only route by which the Velocity forwarding secret ever
  reaches a container.
- `RUN_SANDBOX` — carries the `PodSandboxConfig` that `CreateContainer` must hand
  back verbatim, so it is on the same footing.

`EXEC_SYNC` and `EXEC` are **deliberately absent**: their requests carry an argv,
no call site puts a credential in one — the RCON password reaches `rcon-cli`
through the environment, not the arguments — and their descriptions are the most
useful diagnostic this client produces. Add them the day a call site passes a
secret as an argument, and not before. The predicate is an exhaustive `when`
rather than a set, so adding an RPC will not compile until somebody has decided
which side of the line it falls on.

Two properties follow:

- **`safeMessage` and `safeDescription` are what you log, store, serve over the
  API, or put on observed status.** `message` and `description` stay unredacted so
  a stack trace inside this module still says everything.
- **Do not truncate `description` and log a prefix.** Go renders a rejected request
  from the front, so a prefix of a failed `CreateContainer` error is a prefix of
  the container's environment. Apply any bound to `safeDescription`.

When text is withheld, `WITHHELD_DESCRIPTION` names the runtime's own log on the
node as the place it does exist — which is true, and the two obvious guesses
(this client's log, the server's observed status) both withhold it by design.

---

## Connecting

`CriClient.connect(config)` returns a client over the endpoint in
`CriClientConfig`. The endpoint is a Unix domain socket in the form containerd
tooling uses (`unix:///run/mcorch-dev/containerd.sock`).

UDS needs native epoll, and the channel type and event loop group are set
explicitly: grpc-java's `Utils` picks `EpollSocketChannel` for TCP but never
selects `EpollDomainSocketChannel` on its own. That is why
`netty-transport-native-epoll` appears twice in the build — the plain artifact for
the Java classes, and the `linux-x86_64` classifier at runtime for the native
library.

`maxInboundMessageSizeBytes` defaults to 32 MiB with a 16 MiB floor: a listing on
a busy node is the message that grows.

`shutdown(gracePeriod)` closes the channel, defaulting to 5s.

---

## Testing

The wrapper's unit tests run the real client against a **fake CRI server over
gRPC's in-process transport** — no socket, no netty, no containerd — so
`./gradlew build` stays runnable on a machine with no container runtime.

```bash
./gradlew :cri:test              # in-process, no runtime needed
./gradlew :cri:integrationTest   # against a real containerd
```

The integration suite is deliberately small and deliberately not wired into
`check`. It covers the handful of claims about **containerd's own behaviour** that
the wrapper is built on and that a fake cannot check, because a fake would agree
with whatever the wrapper believes:

- `StopDeadlineCapIT` — what containerd does with a stop whose caller gave up
  part-way through the grace period.
- `StopGracePeriodBoundaryIT` — the boundary values of the grace period.

`compileIntegrationTestKotlin` **is** wired into `check`, and that is load-bearing
rather than tidy: a run needs a containerd nobody has in CI, but a compile needs
nothing, and it is the only thing that tells a change to a shared type it has
broken this source set. Without it, `EndpointRequest.timeout` becoming a value
class silently stopped this source set compiling for a whole round while the
suite reported green over code that excluded it.

---

## Changing the proto

Never hand-edit generated stubs; they are build output under
`build/generated/sources/proto/main/` and are not committed. Regeneration and any
version bump go through the `generate-cri-stubs` skill, and the vendored file's
provenance — upstream repo, tag, retrieval URL, SHA-256 — is recorded in
`cri/PROTO_SOURCE.md` so the next bump is reproducible.

Two things that usually need workarounds here and did not at the pinned versions
(verified 2026-07-26, recheck after a codegen bump): the generated Kotlin compiles
under `explicitApi()` unchanged, and protoc-gen-grpc-java no longer emits
`@javax.annotation.Generated`, so no annotation-api shim is needed on the compile
classpath.
