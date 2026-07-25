---
name: add-server-kind
description: The procedure for adding a new server kind (a new declarable server or proxy type) to this orchestrator, or adding fields to an existing kind. Use this skill whenever the conversation turns to a new kind of server, a new configuration option in a definition, surfacing new observed status, or teaching the reconcile loop a new behaviour — even if nobody says "schema" or "kind". Skipping steps leaves the YAML schema, the reconciler, and validation out of sync.
---

# Adding or changing a server kind

Work that touches a server kind is complete only when all steps are done. A kind that parses but the loop does not honor, or that the loop honors but validation does not guard, is half-built.

## Procedure

### 1. Read an existing kind

Read at least one existing definition type in `:schema` and match its structure, naming, and validation style. Do not invent a new house style.

### 2. Design the schema (schema-designer)

- Spec is what the user declares; status is what the orchestrator observed. Keep them separate.
- New fields are optional with a default; do not break definitions already on disk.
- Defaults sit on the safe side: persistence on, drain-before-stop on.
- Secrets are references to the secret store, never inline fields.
- Provide example YAML, including the malformed cases that must be rejected.

### 3. Validate at parse time

Every constraint (ports, image references, limits) is checked when the YAML is parsed, with a field-level error message. Do not defer validation to the reconciler — an invalid definition should never reach the loop.

### 4. Teach the reconcile loop (reconciler-dev)

- Compute the diff between desired and observed for the new kind and apply the smallest convergent step.
- Idempotent: two passes against the same state produce no new side effects.
- All container operations go through the Node abstraction, never a direct local CRI call.
- If the kind can be stopped, restarted, or replaced, that path goes through the `drain-protocol` skill. Read it before writing.

### 5. Persist the new state (store-dev, if needed)

If the kind introduces new state to store, extend the `Store` interface without leaking storage specifics, and add a forward-only migration for the on-disk schema.

### 6. Write tests

- Schema: valid definitions parse; each invalid case is rejected with the right error.
- Reconcile: an **idempotency test** (two passes, second is a no-op), plus one transient-failure requeue and one permanent-failure surfacing.
- Store (if touched): the interface suite passes against the implementation; migration test with no data loss.

### 7. Verify against real containerd (integration-tester)

Declare one instance of the new kind, confirm the loop brings it to actually-joinable (not just "running"), re-apply with no diff, then remove it and confirm cleanup while persistent data survives.

## Completion checklist

- [ ] Schema type exists in `:schema` with example YAML
- [ ] Parse-time validation covers every constraint with field-level errors
- [ ] Reconcile logic honors the kind and is idempotent
- [ ] Store interface/migration updated if new state was introduced
- [ ] Unit tests: schema validation + reconcile idempotency + failure classification
- [ ] Integration test against real containerd passes
- [ ] `./gradlew build` passes

## Breaking changes

Removing, renaming, or retyping a field needs, in the same change: a field mapping table, a new schema version with a migration/conversion path (old definitions must keep parsing or convert on load), and a decision with reasoning on how long the old version is accepted.

## Common failures

| Symptom | Cause |
|---|---|
| Loop acts on a definition that should have been rejected | Validation deferred to the reconciler instead of parse time (step 3) |
| Reconcile creates duplicate containers | Not idempotent; observed state not recorded or not compared (step 4/6) |
| Old definitions fail to load after a change | A required field was added, or a breaking change shipped without migration |
| Works locally, breaks the distribution seam | The loop assumed single-host instead of going through Node/Scheduler |
