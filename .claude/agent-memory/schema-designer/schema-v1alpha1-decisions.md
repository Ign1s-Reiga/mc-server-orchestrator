---
name: schema-v1alpha1-decisions
description: Why mcorch.dev/v1alpha1 PaperServer is shaped the way it is — YAML library choice, unknown-key rejection, safe defaults, and the rules encoded at parse time
metadata:
  type: project
---

The first server kind (`PaperServer`, `mcorch.dev/v1alpha1`) landed 2026-07-26 and set the house
style for every later kind (Velocity proxy, minigame pool, lobby). The decisions below are the ones
that are *not* obvious from reading the code, and that would otherwise get re-litigated.

**Why:** `:schema` is the project's public contract; the same choices get copied by every later kind,
so the reasoning has to survive the conversation it was made in.

**How to apply:** when adding a kind or a field, follow these unless the human overrules them; see
[[schema-open-questions]] for the calls that are deliberately still up for debate.

- **snakeyaml-engine (low-level `Compose`), not kaml or Jackson.** An object binder fails on the
  first problem and hides the rest. This module reports *every* violation in one parse, each with a
  dotted field path and a line/column, which needs the composed node tree. kaml sits on
  snakeyaml-engine anyway. The library is an implementation detail: no snakeyaml type appears in
  `:schema`'s public API.
- **Unknown keys are rejected, not ignored.** A typo (`persistance:`) that is silently dropped leaves
  the operator believing they configured something they did not — the exact failure class this
  orchestrator exists to prevent. The message includes a "did you mean" suggestion plus the known
  field list. Explicit `null` is also rejected ("omit the field to use its default") for the same
  reason.
- **Two rules are enforced twice**, once as an aggregatable violation in the parser and once as a
  constructor `require`: JVM heap must sit below the container memory limit minus headroom, and the
  stop grace period must exceed the drain save timeout by ≥30s. Doubling them means no other module
  — not even a test fixture — can build a spec that loses world data. The predicates live in one
  internal object so the two paths cannot drift.
- **A bad `metadata.name` does not abort spec validation** (a placeholder name is substituted so the
  rest of the file is still checked), but an unknown `apiVersion`/`kind` does — we genuinely do not
  know which rules apply, and guessing would emit bogus "unknown field" noise.
- **Safe-by-omission is a hard rule.** Omitting `storage` yields a persistent volume named after the
  server; omitting `lifecycle` yields drain-on with a grace period derived from the save timeout
  (never a constant, so raising `saveTimeout` cannot invert the relationship); omitting `heap` sizes
  `-Xmx` = memory − clamp(20%, 512Mi, 2Gi) and `-Xms` = `-Xmx`.
- **No inline secret field exists anywhere, and keys that look like one** (`password`, `token`,
  `forwardingSecret`, …) get a dedicated error pointing at the secret store instead of a generic
  "unknown field". This is the precedent that keeps the Velocity forwarding secret out of YAML.
- **Status is designed for comparison, not just display.** Every status type is a data class of value
  types so the loop can decide "nothing changed" by comparing instead of re-acting, and it carries
  `saveRequestedAt`/`sealRequestedAt`-style timestamps so a re-entered drain state does not re-send
  side effects. Status carries player *counts* only — no names, UUIDs or addresses — so any
  `toString` is safe to log.
