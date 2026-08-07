---
name: schema-violation-message-rules
description: What a validation message in :schema may and may not repeat back to the operator — the no-echo rule on secret paths, why describe() names shapes, and the residual echo paths that are deliberate
metadata:
  type: project
---

Settled 2026-08-08, fixing a leak a `docs/schema.md` reviewer stumbled into: `spec.forwarding:`
written as a scalar (a plausible abbreviation of `spec.forwarding.secret`) was answered with
``expected a mapping, found the value `<what they wrote>` ``, and violations are rendered into API
response bodies and log lines. Same shape on `spec.control:` and `spec.network.rcon:`. None of those
keys is in `SECRET_LIKE_KEYS` — they are *known* fields, so the unclaimed-secret-like-key guard never
sees them.

**Why:** CLAUDE.md invariant 4 says the forwarding secret only ever travels through the secret store.
An operator pasting it into YAML is their mistake; echoing it back out is ours.

**How to apply:** when adding a field, a kind, or any new violation message, check it against the
three rules below before writing the message text.

- **`describe()` names a node's shape, never its value** (`found a string`, `found a number`, `found
  a list`). This is the load-bearing half and it is cheap: `describe()` is only ever used for a
  *shape* mismatch, where the shape is the whole diagnostic, and every violation already carries a
  field path plus `file:line:column`. The messages where quoting the value genuinely helps
  (``expected a whole number, found `x` ``, `must be one of …`, memory/duration parse failures) do
  not go through it, so nothing was lost. Do not "improve" it by adding the value back.
- **`SecretBearingPaths` (in `YamlReading.kt`) is the single list of fields taking a `SecretRef`**,
  plus the block each one sits in. A scalar at one of those paths gets `INLINE_SECRET_PROBLEM`
  instead of "expected a mapping" — that is a *message-quality* choice, not the safety mechanism.
  `MappingReader.secretRef` throws if called at a path the list does not name; `SecretEchoTest`
  walks the list from the other side. Three properties keep that pair a guard rather than a
  comment, and each was a review finding: the check fires when the *enclosing block* is read, so
  it only means "fails immediately" while every container is written by some `valid/` example (now
  asserted); membership is tested on the path with list indices flattened, because `valueList`
  puts a document position in a path and a guard that throws on valid input is worse than none;
  and a reference must not sit directly under `spec`, or the derived container would make
  `spec: <anything>` answer with the secret store on every kind (a `require` refuses it).
- **Inside a `SecretRef`, the `name` and `key` coordinates are described, not quoted** — that is
  where material lands when someone abbreviates the reference away. `nameProblem`/`keyProblem` keep
  the empty and over-length answers (a length is a fact *about* a value, not the value) and fall
  back to the syntax rule instead of quoting; `ResourceName.SYNTAX` and `SecretRef.KEY_SYNTAX` are
  public so that wording has one home. `:api` states the same rule in its own words on
  `/api/v1/secrets/{name}/{key}` rather than relaying `SecretRef.of`, whose text is written for a
  definition file and describes a mistake unreachable over a URL.

Residual echo paths, probed and accepted rather than missed:

- a *key* is part of the field path it is reported on, so `forwarding: {<material>: x}` puts material
  in `spec.forwarding.<material>: unknown field`. Unfixable without violations ceasing to name their
  field, which is a harder contract than this one;
- non-secret fields quote their value, including non-coordinate fields *inside* a secret block
  (`forwarding.mode: <value>`). Deliberate: nobody pastes a secret into `mode`;
- snakeyaml's own text reaches `<document>: is not valid YAML: …`. Only one of its messages
  interpolates operator text — `found undefined alias <name>` — and an alias name is not material.
  Everything else it emits names token kinds and character codes.
