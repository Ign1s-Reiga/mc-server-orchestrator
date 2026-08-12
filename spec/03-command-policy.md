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
| `viewer` | Commands returning no identifiers — `tps`, `mspt`, `seed`, version queries |
| `operator` | Gameplay and moderation — `say`, `whitelist`, `kick`, `ban`, `gamemode`, `give` |
| `admin` | Configuration, and everything else the allow-list permits |

## 3. Allow-list, failing closed

Each tier carries an explicit set of permitted commands. **An unrecognised
command is refused, never passed through.**

Three reasons this must be an allow-list rather than a deny-list:

1. **Output handling requires it.** You cannot safely redact or parse output
   whose shape you have never seen — see [04-output.md](04-output.md). Permitting
   only commands with known output is the same constraint viewed from the other
   side.
2. **Mods and plugins register arbitrary commands.** On a modded fleet a
   deny-list develops a silent hole the moment someone installs a mod. An
   allow-list degrades gracefully instead: the new command is refused until
   somebody adds it deliberately.
3. **Failing closed is the only safe direction** for a facility with full server
   authority.

---

## 4. Per-server ceiling, declared in YAML

A `PaperServerDefinition` declares the highest tier it will accept:

```yaml
spec:
  console:
    maxTier: operator      # this server refuses admin-tier console entirely
```

So a production survival server can refuse `admin` console outright while a test
server allows it. This is a bound the operator writes down, independent of who
logs in, in the same place every other decision about a server is written — and
it is where the op-level concept genuinely earns its place.

The effective tier for a request is `min(identity tier, server ceiling)`.

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
