# Forced termination — specification

> **Status: proposed. Nothing here is implemented.**
>
> This specification describes a path that **can lose world data**. That is its
> purpose, and every document here is written so that nobody can adopt it without
> having read that sentence.

Two changes, decided together:

1. **RCON becomes standard.** The manifest stops declaring *whether* RCON is used
   and declares only *how* — [01-mandatory-rcon.md](01-mandatory-rcon.md).
2. **A forced termination path exists**, restricted to `Superuser` —
   [02-force-stop.md](02-force-stop.md).

They arrived from the `DELETE` question in
[`../auth/03-authorization.md`](../auth/03-authorization.md) §3.5, which is now
settled and points here.

## What this actually closes, and what it does not

The proposal came with the reasoning that mandatory RCON *"eliminates any
scenarios that were previously uncovered."* **Half of that is right, and the half
that is not is the half the force button gets used for.**

| Scenario | Closed by mandatory RCON? |
|---|---|
| A *new* persistent server declared without RCON, undeletable forever | **Yes.** It can no longer be declared |
| An *existing* container created without RCON | **No.** `docs/schema.md`: *"a persistent server created without RCON keeps running and cannot be given it"* — the edit needs a recreate, the recreate needs a drain, the drain needs the channel that is not there. `DrainTest` has a test for exactly this deadlock |
| RCON configured, but the main thread is wedged | **No.** `docs/failure-modes.md`: *"SLP answers, RCON does not. Attempt the save and wait the full save timeout, then abort unconfirmed"* |
| RCON configured, main thread busy during world generation | **No.** Same file: *"when RCON is up but the main thread is busy for a minute or more — becomes permanently undeletable"* |
| The RCON password is wrong | **No.** Permanent in practice and indistinguishable from a rotated one |

**RCON being *configured* is not RCON being *responsive*.** Three of the five
rows survive the schema change untouched.

So the thing that actually closes the gap is the **force path**, not the schema
change. The schema change stops new instances of the easiest case; the force path
is what makes the remaining four exitable.

That matters for one reason, and it is the whole design constraint of
[02-force-stop.md](02-force-stop.md):

> **The button gets reached for precisely when RCON is not answering — which is
> precisely when a save may be in flight.** The drain-protocol skill: *"A kill
> mid-save can corrupt region files."*

The case you most want the button in is the case where an immediate kill is most
dangerous. So the button is not an immediate kill.

## Settled decisions

| Decision | Ruling |
|---|---|
| RCON in the manifest | Standard. `spec.network.rcon` declares port and secret, not `enabled` |
| Old definitions carrying `enabled: false` | Rejected at parse with a field-level error, not silently migrated — [01-mandatory-rcon.md](01-mandatory-rcon.md) §3 |
| Force path | `DELETE /api/v1/servers/{name}?force=true`, `Superuser` only |
| Force semantics | Attempt the save, wait the save timeout, **then** stop regardless of confirmation. Not an immediate kill |
| True immediate kill | Stays out of band — `crictl stop --force`, which the orchestrator does not offer and cannot see |

## Before any of this is implemented

CLAUDE.md: *"Merge drain-related code at the 'seems to work' stage. It always goes
through `drain-auditor` first."* Every change in this specification is
drain-related by definition. The audit is not optional and is not satisfied by
this document.
