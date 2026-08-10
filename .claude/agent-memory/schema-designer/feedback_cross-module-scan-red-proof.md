---
name: cross-module-scan-red-proof
description: A mutation planted outside the module under test reads GREEN because the build cache restores the result — red-proof cross-module scans with --rerun-tasks --no-build-cache
metadata:
  type: feedback
---

When a test in module A reads module B's **sources** — any structural scan whose
claim is about the repository rather than about one module — a sabotage planted
in B does not invalidate `:A:test`. Gradle sees unchanged inputs, restores the
task from the build cache, and exits 0. The mutation reads as **surviving** when
nothing ran at all. `cleanTest` does not fix it: deleting the outputs just makes
the cache hand them back.

Red-proof those scans with `./gradlew :A:test --tests ... --rerun-tasks
--no-build-cache`.

**Why:** measured on 2026-08-08 while red-proofing `ControlCredentialWiringTest`,
which scans every module for readers of a `:schema` property. A gate planted in
`:app` read GREEN under `:core:cleanTest :core:test` and RED under
`--rerun-tasks --no-build-cache`, with the identical working tree. Two of the
three cross-module mutations in that round were nearly reported as gaps in the
scan; the scan was fine and the harness was lying. It is the same failure shape
as [[queued-findings-need-their-own-fact]] one level down: the instrument was
measured instead of the code.

**How to apply:** before believing any GREEN in a mutation run, ask whether the
mutated file is an *input* to the task you ran. Same-module main sources are;
another module's sources read as files are not, and neither is anything
downstream of the module under test. When a mutation is reported as surviving,
re-run it forced before writing that down — and when a mutation script fails to
apply its pattern at all (a stale anchor after the fix moved), that is also not a
survival: it prints a traceback and still exits into the GREEN branch unless the
script checks. Make the script fail loudly on a pattern count that is not one.
