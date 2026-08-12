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

## 3. This is a breaking schema change

Per the `add-server-kind` procedure, breaking changes need a field mapping, a
version with a migration path, and a decision on how long the old form is
accepted. All three, plus the one this change makes unusual.

### Field mapping

| Old | New |
|---|---|
| `rcon.enabled: true` + `passwordSecret` | `rcon.passwordSecret` — the `enabled` key is dropped |
| `rcon.enabled: true`, no `passwordSecret` | Already invalid today; still invalid |
| `rcon.enabled: false` | **No mapping exists.** §3.1 |
| `rcon` absent entirely | **No mapping exists.** Same reason — the default was `disabled` |

### 3.1 Why `enabled: false` cannot be migrated silently

A migration converts a definition into an equivalent one. There is no equivalent
here: the new form requires a `passwordSecret`, and **nothing can invent one**.

- Generating a secret during a migration produces material nobody asked for and
  nobody holds, in a store the operator did not write to.
- Defaulting to some shared secret makes every server share an RCON password,
  which is the kind of thing that is discovered years later.
- Leaving it absent produces a definition that fails its own validation.

So a definition carrying `enabled: false`, or carrying no `rcon` block at all, is
**rejected at parse with a field-level error** naming the field and saying what to
supply. Loud, at the moment the operator can act, rather than a surprise later.

### 3.2 The migration is an operator action, not a data migration

The store keeps definitions as written. On upgrade, a stored definition without an
RCON secret becomes unreadable rather than invalid-but-loaded — which the API
already models: `SERVER_UNREADABLE`, rendered as a row with `unreadable.reason`
rather than omitted, and repairable by `PUT`ting a valid definition with
`If-Match: *`.

That path exists and is tested. This change makes it the upgrade experience for
any fleet holding an RCON-less definition, so the release note has to say so.

## 4. What this does *not* fix

**A container already running without RCON is unchanged by any of this.**

The schema governs what may be *declared*. A container created before the change
was created with RCON disabled and has nothing listening; the new definition asks
for a recreate, the recreate needs a drain, and the drain needs the channel that
is not there. `DrainTest`'s *"enabling RCON on a container that has none does not
wedge the server"* pins that exact deadlock, and the loop's correct behaviour is
to refuse and tell the operator to revert.

**Those servers are retired by the force path or by hand, and by nothing else.**
See [02-force-stop.md](02-force-stop.md). Any claim that making RCON standard
resolves them is wrong, and the reasoning in `docs/schema.md` §"later is too late"
is what makes it wrong.

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
