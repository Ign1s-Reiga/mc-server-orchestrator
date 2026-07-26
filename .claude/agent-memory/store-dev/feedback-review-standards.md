---
name: feedback-review-standards
description: What code-reviewer holds :store to — failures classified by code not message text, concurrency claims proved against the pre-fix code, and honesty about non-deterministic tests
metadata:
  type: feedback
---

**Classify failures by a stable code, never by matching substrings in a message.** This applies to any dependency's error surface, not just SQLite's.
**Why:** `:cri` already documents the rule (`ErrorTranslation.kt`: "descriptions are free-form and change between releases"), and `:store` was called out in review for breaking it — a `SQLITE_BUSY` whose wording drifts in a driver bump silently becomes a permanent failure and drops a server from the reconcile queue until the resync. Review checks the modules against each other, so a pattern one module rejected is expected to stay rejected in the others.
**How to apply:** naming a vendor type (`org.sqlite.SQLiteErrorCode`) inside an `internal` package backed by an `implementation` dependency is fine and was explicitly blessed — the boundary is the public interface, not the import list. Pair it with a test whose fixture *messages disagree with their codes*, so a reversion to substring matching fails rather than passes.

**When fixing a concurrency defect, run the new test against the pre-fix implementation and report the hit rate.**
**Why:** review asked for a deterministic test "or say so rather than shipping a flaky one". A stress test that never fails on correct code but only probabilistically catches the bug is acceptable — provided the claim is measured rather than asserted.
**How to apply:** back up the fixed file, restore the defect, run the test several times, restore. Shape the test to make the window wide (large buffers, readers looping rather than trying once, a head start before the destructive call) instead of hoping for a lucky interleaving.

**Deviating from a reviewer's prescribed mechanism is fine if the mechanism does not close the hole — say why in the code.**
**Why:** the review asked for `@Volatile` plus a compare-and-set on `SecretValue.destroyed`. A CAS makes the wipe happen once but does not stop a `use` that already passed the check from copying a half-wiped buffer, so the fix took a lock for the copy/wipe critical section and kept `@Volatile` for the lock-free flag read.

See [[store-design-decisions]] for the module's other load-bearing choices.
