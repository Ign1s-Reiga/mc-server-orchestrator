# Forced termination — specification

> **Status: the forced path is implemented and has been through `drain-auditor`
> once. Round 49 returned three criticals; all are addressed and the change has
> not been re-audited.** RCON-as-standard shipped separately.
>
> It is **not** built the way §2 of this document described. The specification
> called for three exemptions inside the drain; the code said no, and the
> implementation note in `02-force-stop.md` §2 records why and what was built
> instead.
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
| An *existing* container created without RCON | **Moot.** Pre-release: nothing is running |
| RCON configured, but the main thread is wedged | **No.** `docs/failure-modes.md`: *"SLP answers, RCON does not. Attempt the save and wait the full save timeout, then abort unconfirmed"* |
| RCON configured, main thread busy during world generation | **No.** Same file: *"when RCON is up but the main thread is busy for a minute or more — becomes permanently undeletable"* |
| The RCON password is wrong | **No.** Permanent in practice and indistinguishable from a rotated one |

**RCON being *configured* is not RCON being *responsive*.** Three of the five
rows survive the schema change untouched, and they are the live ones.

So the thing that actually closes the gap is the **force path**, not the schema
change. The schema change removes the declarable case; the force path is what
makes the three unresponsive ones exitable.

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
| Old definitions carrying `enabled: false` | Rejected at parse as an unknown field. No migration — pre-release, nothing to preserve — [01-mandatory-rcon.md](01-mandatory-rcon.md) §3 |
| Force path | `DELETE /api/v1/servers/{name}?force=true`, `Superuser` only |
| Force semantics | Attempt the save, wait the save timeout, **then** stop regardless of confirmation. Not an immediate kill |
| True immediate kill | Stays out of band — `crictl stop --force`, which the orchestrator does not offer and cannot see |
| Reporting an unresponsive RCON | From use and from the drain, never from a poller — [01-mandatory-rcon.md](01-mandatory-rcon.md) §4.1. A probe is tick budget spent on every server forever, to detect what a failed command already reports |
| `RouteTableTest` | Rewritten, not deleted, in the change that adds the force path — [02-force-stop.md](02-force-stop.md) §5 |

## Owed, and not left in a comment

The forced path drops drain steps by construction, not by oversight. Two were
recorded here as work rather than as caveats in a KDoc. One has since been done,
and **the reason it stopped being optional is the interesting part**:

- ~~**Seal the proxy before the stop.**~~ **Done.** It was listed as a follow-up
  on the reading that it only cost availability — the proxy routing joins at an
  address that is going away. That reading was wrong. Once the occupancy check
  became a counted acknowledgement, the seal became the thing that check *rests
  on*: a compare-and-swap is only a compare-and-swap if something owns the value
  between the read and the write, and on an unsealed server nothing owns the
  player count for a millisecond. The mechanism was not implementable without
  step 2. A follow-up that turns out to be a premise is not a follow-up.
- ~~**Attempt a player transfer.**~~ **Done.** The drain's step 4, issued once and
  given `spec.lifecycle.drain.playerTransferTimeout` to get somewhere, then the
  stop proceeds with whoever is left. Never a refusal: a proxy that cannot resolve
  a destination, a fleet with no capacity, a sweep that will not run — none of
  them is a reason to leave a server unretirable, which is the state this endpoint
  exists to remove.

  Attempted only under an asserted seal, and only before the deciding probe. The
  first because sweeping players off a server that is still admitting races logins
  the sweep cannot see the end of, which is why the drain never reaches step 4
  from a state that has not held the door shut. The second so the count the
  acknowledgement is checked against is the one that survives the attempt.

  The first draft put the sweep *above* the deciding refusal, which forced the
  acknowledgement's comparator open to "at most n" so a partial sweep would not be
  refused over the players it left. That was a bypass: any large number then
  satisfied any population, which is the boolean this design rejects, respelled.
  The sweep sits below the refusal instead, and the two readings are kept apart —
  the acknowledgement settles what the operator was shown, and a separate check
  refuses only if the count has **risen** since.

Nothing is owed now. The rest are deliberate and permanent: `requireEmpty` is
replaced by a counted acknowledgement read under the seal, the save is always
requested, deregistration is left to the loop, and `mayStop` is bypassed — which
is the feature.

**One residual, stated rather than buried.** A *standalone* server has no proxy,
so there is no door to shut and the seal cannot help it. The count is re-read
immediately before the stop there, which narrows the window to milliseconds but
does not close it. The drain does not close it either — it declines to stop
instead — and an operator forcing an unproxied server with a live population is
told to let it empty rather than handed a guarantee that does not exist.

## Before any of this is implemented

CLAUDE.md: *"Merge drain-related code at the 'seems to work' stage. It always goes
through `drain-auditor` first."* Every change in this specification is
drain-related by definition. The audit is not optional and is not satisfied by
this document.
