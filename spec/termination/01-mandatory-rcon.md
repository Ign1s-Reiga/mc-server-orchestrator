# 01 — RCON becomes standard

## 1. The change

`spec.network.rcon` stops declaring *whether* and declares only *how*.

```yaml
# before
network:
  rcon:
    enabled: true
    port: 25575
    passwordSecret: { name: survival-01-rcon, key: password }

# after
network:
  rcon:
    port: 25575                                                  # default 25575
    passwordSecret: { name: survival-01-rcon, key: password }    # required
```

`RconSpec` stops being a sealed interface with `Disabled` and `Enabled` and
becomes a single type. Every `when` over it collapses, and — usefully — the
compiler finds them, the way `NodeOperation.CONSOLE` did.

## 2. Why it is worth a breaking change

The disabled case is the source of `docs/operating.md`'s first documented
surprise: a persistent server that cannot be deleted, ever, because the world
save cannot be confirmed. `docs/schema.md` currently has to spend a section
telling operators *"later is too late"* — that enabling RCON afterwards cannot
work, because the edit needs a recreate and the recreate needs the drain that
needs the channel.

A field whose wrong value is unrecoverable, and whose correct value is what
everybody wants anyway, is a field that should not exist. Removing it deletes an
entire class of support question.

It also makes the remote console (`../README.md`) universally available, which is
the stated goal.

## 3. Breaking, and cheap because the project is pre-release

`RconSpec` loses a case, so definitions written against the old shape stop
parsing. Normally the `add-server-kind` procedure would require a field mapping,
a schema version and a migration path.

**None of that is needed here.** There is no deployed fleet and no stored
definition to preserve, so the old form is simply rejected:

| Old | Now |
|---|---|
| `rcon.enabled: true` + `passwordSecret` | Drop the `enabled` key. Otherwise unchanged |
| `rcon.enabled: false` | Rejected — supply a `passwordSecret` |
| `rcon` absent | Rejected — the block is required |
| `enabled` present at all | Rejected as an unknown field |

`enabled: false` is rejected rather than translated because there is nothing to
translate *to*: the new form requires a `passwordSecret` and nothing can invent
one. Generating material during a load produces a secret nobody holds; defaulting
gives a fleet one shared password. Both are worse than an error message.

Rejecting `enabled` as an **unknown field** rather than ignoring it is the part
worth being deliberate about. A definition still carrying `enabled: false` means
its author believed RCON was off, and silently enabling it would be the
orchestrator doing the opposite of what the document says. The schema already
treats unknown fields as errors; this inherits that.

> Revisit if this ships. Once definitions exist that somebody would lose, a
> change of this shape needs the machinery this section is skipping.

## 4. What this does *not* fix

The RCON-less container is gone as a concern — pre-release, nothing is running.
**Three cases survive untouched**, and they are the reason
[02-force-stop.md](02-force-stop.md) exists rather than being made redundant by
this change:

| Case | Why the schema cannot reach it |
|---|---|
| The main thread is wedged | `docs/failure-modes.md`: *"SLP answers, RCON does not. Attempt the save and wait the full save timeout, then abort unconfirmed"* |
| The main thread is busy generating world | Same file: *"when RCON is up but the main thread is busy for a minute or more — becomes permanently undeletable"* |
| The password is wrong | Permanent in practice, and indistinguishable from one that was right and has been rotated |

**Configured is not responsive.** A definition can guarantee the port is open and
the secret exists; it cannot guarantee anything answers. Every one of these leaves
a fully RCON-enabled server unable to confirm a save, which is exactly the state
`DELETE` cannot complete from.

## 4.1 Surfacing an unresponsive RCON

The dashboard reports it. Two sources, and **neither is a poller**:

- **A console command that fails.** `CONSOLE_TIMEOUT` and `CONSOLE_UNAVAILABLE`
  already carry it — see [`../07-api.md`](../07-api.md) §3. An operator who tried
  to use the console learns immediately, in the place they were looking.
- **A drain that could not confirm.** `status.drain` already reports this, and
  `DRAIN_FAILED` already means *"the drain aborted and the server is still
  running"*. Nothing new is needed for it to reach a client.

**Do not add a periodic RCON health probe.** Per
[`../05-concurrency.md`](../05-concurrency.md), RCON dispatches onto the game's
main thread — so a probe is not a read, it is tick budget spent. Every server,
every interval, forever, to detect a condition the two sources above already
report when it matters. On a modded fleet with a tight tick budget that is a
monitor that degrades the thing it monitors.

If a liveness signal independent of use is genuinely wanted later, **Server List
Ping is the one to use**: `docs/failure-modes.md` records that SLP is served from
the Netty IO thread off a cached status object and *"still answers while the main
thread is wedged"* — which makes it both cheaper and a better discriminator, since
SLP answering while RCON does not is precisely the condition being looked for.

## 5. What the change owes

- `RconSpec` collapsed in `:schema`, and every consumer updated in the same
  change — `PaperWorkload`, `PaperServerAgent.contractOf`, the drain's
  save-confirmable reasoning, `Labels.SAVE_CONFIRMABLE`.
- Parse-time validation: `passwordSecret` required, `port` defaulted to 25575 and
  still required to differ from `network.port`.
- `docs/schema.md` — the *"RCON does nothing until you enable it"* and *"later is
  too late"* sections both go, and their replacement says the secret is required.
- `docs/operating.md` note 1 — the undeletable RCON-less server stops being
  reachable by declaration, but **does not stop existing**, so the note narrows
  rather than disappears.
- `api/API.md` §6's spec-field list, and its *"Choices that are hard to reverse
  after creation"* section.
- `Labels.SAVE_CONFIRMABLE` becomes constant for `PaperServer`. It should
  **not** be deleted: it still distinguishes a Paper server from a
  `VelocityProxy`, which has no RCON and never will.
