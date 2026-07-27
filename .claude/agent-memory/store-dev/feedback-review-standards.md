---
name: feedback-review-standards
description: What :store is held to — failures classified by code not message text, every new test proved against a broken implementation, and guards that leave shipped code shape untouched
metadata:
  type: feedback
---

**Classify failures by a stable code, never by matching substrings in a message.** This applies to any dependency's error surface, not just SQLite's.
**Why:** `:cri` already documents the rule (`ErrorTranslation.kt`: "descriptions are free-form and change between releases"), and `:store` was called out in review for breaking it — a `SQLITE_BUSY` whose wording drifts in a driver bump silently becomes a permanent failure and drops a server from the reconcile queue until the resync. Review checks the modules against each other, so a pattern one module rejected is expected to stay rejected in the others.
**How to apply:** naming a vendor type (`org.sqlite.SQLiteErrorCode`) inside an `internal` package backed by an `implementation` dependency is fine and was explicitly blessed — the boundary is the public interface, not the import list. Pair it with a test whose fixture *messages disagree with their codes*, so a reversion to substring matching fails rather than passes.

**Every new test gets proved against a broken implementation before it ships — name the test and the mutation in the report.**
**Why:** review asked for a deterministic test "or say so rather than shipping a flaky one", and the user has since asked for this on plain non-concurrent tests too ("prove each new test bites by breaking the behaviour it covers"). A test that passes on both the fixed and the broken code is documentation, not a guard.
**How to apply:** back up the file, mutate exactly the behaviour under test (`if (x)` → `if (false)` is usually enough), run, restore. Aim for a mutation only the *new* test catches, so the report can say which one bit. For a concurrency defect the same procedure applies but the claim is a measured hit rate over several runs, not a single failure — shape the window wide (large buffers, readers looping, a head start before the destructive call) instead of hoping for a lucky interleaving.

**Prefer a guard that leaves the shipped code shape untouched over one that reformats it.**
**Why:** hoisting version 3's duplicate-key check above its `when` instead of nesting it inside a branch kept the migration's decision table byte-identical in the diff, in a file whose own rules say it may never be edited again. `spotlessApply` expands sibling branches into blocks the moment one becomes a block, so an in-branch guard churns lines that carry signed-off semantics.
**How to apply:** fire the guard on exactly the condition that would have failed, then leave the original expression alone.

**Deviating from a reviewer's prescribed mechanism is fine if the mechanism does not close the hole — say why in the code.**
**Why:** the review asked for `@Volatile` plus a compare-and-set on `SecretValue.destroyed`. A CAS makes the wipe happen once but does not stop a `use` that already passed the check from copying a half-wiped buffer, so the fix took a lock for the copy/wipe critical section and kept `@Volatile` for the lock-free flag read.

See [[store-design-decisions]] for the module's other load-bearing choices.
