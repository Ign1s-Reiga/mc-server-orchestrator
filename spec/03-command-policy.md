# 03 — Command policy

Two gates, not one ladder. Collapsing them puts the most dangerous commands at
the top of a privilege ladder, where the most trusted user reaches them — which
is exactly backwards for the two commands that lose data.

---

## 1. Gate 1 — invariant refusals

A fixed set, refused for **every** identity, tier and token. Not grantable, not
configurable, not reachable by any privilege.

| Refused | Because |
|---|---|
| `stop` | Ends the container with players on it — the first invariant, and the whole reason the drain protocol exists |
| `save-off` | Silently stops the world saving; breaks the third invariant, and nothing surfaces it until a drain fails |
| anything ending the server process | `stop` by another name, including plugin- and mod-provided variants |

Refusal returns `409` with a pointer to the declarative path —
`DELETE /api/v1/servers/{name}`, which drains. See [07-api.md](07-api.md).

### 1.1 Why this is not a permission

`RouteTableTest` asserts that no *route pattern* contains `stop`, `kill`, `force`
or `purge`, with a comment stating the reason: *"one that could stop a container
directly could stop one with players on it."*

A route named `/console` passes that test while carrying `stop` in its request
body. **The guard has to move into the command policy**, or the console
reintroduces precisely the thing the route table was written to prevent — just
spelled differently and routed through an operator's keyboard.

An `admin`-tier operator still must not be able to `stop` a server outside the
drain. That is not a question of trust; it is a property of the system.

### 1.2 Why op levels cannot express this

Op levels sort commands by in-game griefing risk. That grouping is wrong here:

| Command | Op level | What it means here |
|---|---|---|
| `save-all` | 4 | Harmless — the drain already runs it |
| `save-off` | 4 | Silently stops world saving; breaks invariant 3 |
| `stop` | 4 | Ends the container with players on it; breaks invariant 1 |
| `ban`, `kick`, `op` | 3 | Socially significant, no data-loss risk |

Level 4 holds both the command the drain itself uses and the two most dangerous
strings an operator can type. No threshold over that ordering separates them.

---

## 2. Gate 2 — permission tiers

Enforced **by the orchestrator**, before the command reaches RCON.

This is not a design choice. RCON executes through the console sender, which
holds permission level 4 unconditionally; `permissions.yml` governs players and
plugins, and `ConsoleCommandSender` satisfies every node in it. **Minecraft
cannot scope an RCON connection**, so there is nothing to delegate to and nothing
to synchronise with. Op level can be a familiar *label* for our tiers; it can
never be an enforcement mechanism.

Drafted with orchestrator-native names — see open decision 2 in
[README.md](README.md).

| Tier | Holds |
|---|---|
| `viewer` | An allow-listed set returning no identifiers — `tps`, `mspt`, `seed`, version queries |
| `operator` | An allow-listed set covering gameplay and moderation — `say`, `whitelist`, `kick`, `ban`, `gamemode`, `give` |
| `admin` | **Anything Gate 1 permits.** The general console |

## 3. Two tiers allow-list; the top tier does not

[04-output.md](04-output.md) settles that raw output crosses the boundary and the
console is general-purpose. A general console cannot be allow-listed — an
allow-list is exactly the thing that stops a Forge mod's custom command from
working — so `admin` is bounded by Gate 1 and by nothing else.

The lower two tiers keep explicit sets, and **an unrecognised command is refused
there, never passed through**. Two reasons that survive the output decision:

1. **Mods and plugins register arbitrary commands.** On a modded fleet a
   deny-list develops a silent hole the moment somebody installs a mod. An
   allow-list degrades gracefully: the new command is refused until somebody adds
   it deliberately.
2. **Failing closed is the right direction for a bounded tier.** `viewer` exists
   to be safe to hand out; a `viewer` that inherits every mod command as it is
   installed is not.

> An earlier draft required an allow-list at every tier, on the grounds that
> output whose shape you have never seen cannot be safely handled. That reason
> died with the typed-parser design — under a general console, unknown output is
> the normal case and is returned as-is. The two reasons above are what is left,
> and they are about bounding a *tier*, not about handling output.

**What the tiers now mean.** `viewer` and `operator` are capability sets; `admin`
is unrestricted server authority minus the two commands that lose data. Granting
`admin` is granting the server console, and should be described that way to
whoever grants it.

---

## 4. Per-server ceiling, declared in YAML

A `PaperServerDefinition` declares what console it will accept:

```yaml
spec:
  console:
    maxTier: operator          # refuses admin-tier console on this server entirely
    auditCommandText: false    # what the audit record keeps — see below
```

So a production survival server can refuse `admin` console outright while a test
server allows it. This is a bound the operator writes down, independent of who
logs in, in the same place every other decision about a server is written — and
it is where the op-level concept genuinely earns its place.

The effective tier for a request is `min(identity tier, server ceiling)`.

### 4.1 `auditCommandText`

Whether the audit record keeps the command **as dispatched**, or only its verb
and argument count.

| | Recorded |
|---|---|
| `false` *(default)* | `kick`, 1 argument |
| `true` | `kick Alice` |

An argument can be a player name, and the audit log is the one sink guaranteed to
be written to disk and read later. `false` is therefore the default: it is the
setting that agrees with CLAUDE.md's standing rule — *never log player names,
UUIDs, or IP addresses* — and turning it on is an operator deliberately
overriding that convention for one server, which is a decision they should have
to write down.

It changes **only** what the audit keeps. It does not affect what the console
returns, which is raw either way ([04-output.md](04-output.md)), and it never
affects whether a command runs.

### 4.2 What building this requires

Per the `add-server-kind` procedure, in one change:

- `ConsoleSpec` in `:schema`, optional with defaults, so definitions already on
  disk keep parsing;
- parse-time validation with field-level errors — `maxTier` against the known
  tiers, and the pair rule that `console` on a `VelocityProxy` is an
  unknown-field error, since a proxy has no RCON;
- the field honoured where the audit record is written, not read later and
  applied by the reader;
- `docs/schema.md`, which is the only operator-facing description of the spec
  fields, and `api/API.md` §6's spec-field list;
- schema tests for both defaults and each rejection, and a test that the audit
  record differs between the two settings.

**Neither field is read by anything until the audit sink and the tier gate
exist.** Landing the schema first would produce a field the loop does not honour,
which the procedure names as the first way this kind of change goes wrong.

This is a `:schema` change and goes to one agent together with its consumers, per
the repository's rule about changes spanning `:schema`. It needs:

- the field, its default and its bounds in `:schema`;
- validation and a violation message;
- the operator-facing description in `docs/schema.md`, which is the only place
  `VelocityProxy` and the spec fields are described for operators;
- the reconciler reading it, and `api/API.md` §6 listing it among spec fields.

**Default:** the safe side. A definition that says nothing about `console` gets
the most restrictive tier, not the most permissive — consistent with how
`holdsWorldData` defaults to `true` and storage defaults to persistent.
