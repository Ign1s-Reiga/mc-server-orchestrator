# Memory Index

- [Store design decisions](store-design-decisions.md) — why CAS over transactions, generation vs resourceVersion, tombstone delete, own-format codec, decode-time deadline clamps.
- [Store open questions](store-open-questions.md) — what `:store` deliberately omits, and the purge-time drain guard most likely to be overruled.
- [Repo environment gotchas](repo-environment-gotchas.md) — offline Gradle, unsigned agent commits, the NUL-byte escape hazard, and what the shared example YAMLs hold up.
- [Review standards for :store](feedback-review-standards.md) — classify by code not message, prove every new test against a broken build, justify deviations.
