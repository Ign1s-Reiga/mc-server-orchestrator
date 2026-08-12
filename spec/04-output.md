# 04 — Command output

## Decision

**The structural no-PII guarantee is preserved. The relay never returns raw RCON
text to `:api`.**

Each allow-listed command has a **parser** producing a typed result. Identities
are dropped at parse time rather than filtered at render time.

```
/list  ──►  parser  ──►  { online: 3, max: 20 }
                          ▲
                          the same shape status.players already uses;
                          the names never become part of any object
```

A command whose output does not parse returns a **parse failure**, not the raw
string — failing closed, exactly as the allow-list does.

`api/API.md` §13 therefore keeps holding verbatim:

> **No player names, UUIDs or client addresses.** Not by filtering — there is
> nothing in the objects to leave out.

`ResponseLeakageTest` extends to the console endpoint with **no carve-out**, and
the guarantee stays absolute and testable rather than becoming "every endpoint
except one".

---

## 1. What this costs

Stated plainly, because it is a standing obligation rather than a one-time cost:
**this is not a general console.** It is a set of typed operations wearing a
console's clothes.

- Every command needs a parser written for it before it can be offered.
- A Forge mod's custom command cannot be supported until somebody writes one.
- The allow-list of [03-command-policy.md](03-command-policy.md) is bounded from
  below by the parser set: a command with no parser cannot be allow-listed, so
  the two lists are one list.

On a modded fleet this is ongoing maintenance. It is the price of the guarantee.

## 2. The asymmetry — names go in, names never come out

This consequence is not obvious and is worth surfacing before it is discovered
during implementation.

Moderation commands take an identity as an **argument**: `kick <player>`,
`ban <player>`, `whitelist add <player>`. So the request body carries a player
name, while §13 guarantees no response ever does.

The endpoint is deliberately asymmetric. But it means:

> **The dashboard cannot show an operator who is online, so it cannot offer a
> player to click.** An operator moderating through this console supplies the
> name from somewhere else — in-game, a report, a chat platform — and types it.

That is a real limit on how useful the `operator` tier is in a dashboard, and it
follows directly from the guarantee rather than from this design. Closing it
would mean returning player identities to the client, which is the thing §13
exists to prevent. It is recorded here as accepted, not solved.

It is also why the audit sink records the **matched allow-list entry** rather
than the raw input (§5): raw input is where the identities are.

## 3. Initial parser set

The starting scope. Each entry is a command, the shape it produces, and the tier
it sits in.

| Command | Result shape | Tier |
|---|---|---|
| `list` | `{ online, max }` — names discarded | `viewer` |
| `tps` | `{ tps1m, tps5m, tps15m }` | `viewer` |
| `mspt` | `{ mean, median, p95 }` | `viewer` |
| `seed` | `{ seed }` | `viewer` |
| `version` | `{ name, version }` | `viewer` |
| `whitelist list` | `{ count }` — names discarded | `viewer` |
| `say <message>` | `{ delivered: true }` — no output to parse | `operator` |
| `kick <player> [reason]` | `{ applied: true }` | `operator` |
| `ban` / `pardon <player>` | `{ applied: true }` | `operator` |
| `whitelist add` / `remove <player>` | `{ applied: true }` | `operator` |
| `gamemode <mode> <player>` | `{ applied: true }` | `operator` |
| `give <player> <item> [n]` | `{ applied: true }` | `operator` |
| `difficulty <level>` | `{ difficulty }` | `admin` |
| `weather` / `time set` | `{ applied: true }` | `admin` |
| `save-all` | `{ saved: true }` | `admin` |

Notes on three of these:

- **`save-all` is permitted** and is not a Gate 1 refusal. It is what the drain
  itself runs; the dangerous neighbour at the same op level is `save-off`, which
  is refused. See [03-command-policy.md](03-command-policy.md) §1.2.
- **`whitelist list` returns a count, not names.** Under §13 there is no other
  option, and a count is genuinely useful for confirming an edit landed.
- **The `{ applied: true }` shapes are thin on purpose.** Most mutating commands
  return a confirmation sentence containing the player's name, so the parser's
  job is to confirm the sentence matched the expected form and then discard it.

**Exact output formats must be verified against the actual server image at
implementation time.** They vary by Paper version and are not part of any
contract this repository controls; a parser written from memory is a parser that
silently mis-reads. Where a format cannot be confirmed, the command does not ship
— failing closed applies here too.

## 4. Why not raw text with masking

Recorded so the alternative is not re-proposed.

Returning raw output and masking the sensitive parts **fails open**. Output
formats vary by server version and by plugin, and a Forge mod returns text in a
shape nobody has seen. Unknown format means unmasked — so the failure mode leaks,
silently, on exactly the deployments hardest to test.

A worked example of the polarity problem: `/banlist ips` sits at op level 3. A
scheme masking for lower tiers and revealing for higher ones shows asterisks to
the level-3 user and raw client IP addresses to the level-4 one.

The general-console capability that raw text would buy is real, and it is what
this decision gives up. What it would have cost is an absolute guarantee becoming
a conditional one — and a conditional guarantee is what every future reader and
reviewer has to remember, forever, about a codebase whose tests currently make
the absolute version checkable.

---

## 5. The audit sink

Independent of everything above: the audit record is **unconditionally redacted**
and never tiered.

The coding convention — *never log player names, UUIDs, or IP addresses* — has no
per-user exemption, and a design in which the most privileged session writes the
most PII into the logs is backwards. The tier controls what a human is shown in a
live session; it never controls what is written down.

| Field | Notes |
|---|---|
| identity | who ran it — see [06-auth.md](06-auth.md) |
| server | the declared object's name |
| command | the allow-list entry that matched, **not** the raw input |
| tier | the effective tier the request ran at |
| at | timestamp |
| outcome | executed, refused-by-gate-1, refused-by-tier, unavailable, timed out |

**Not the output**, and **not the raw input.** The output is where identities
appear in replies; the raw input is where they appear in requests (§2). Neither
is needed to answer the question an audit log exists to answer, which is who did
what and when.
