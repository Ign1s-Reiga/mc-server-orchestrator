# Memory Index

- [Store design decisions](store-design-decisions.md) — why CAS over transactions, generation vs resourceVersion, tombstone delete, own-format codec.
- [Store open questions](store-open-questions.md) — what `:store` deliberately omits, and the purge-time drain guard most likely to be overruled.
- [Repo environment gotchas](repo-environment-gotchas.md) — offline Gradle, unsigned agent commits, and the NUL-byte hazard when writing unicode escapes.
- [Review standards for :store](feedback-review-standards.md) — classify by code not message, measure concurrency tests against the pre-fix code, justify deviations.
