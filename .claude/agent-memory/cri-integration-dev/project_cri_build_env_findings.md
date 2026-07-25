---
name: cri-build-env-findings
description: Verified 2026-07-26 — protobuf-gradle-plugin 0.10.0 works on Gradle 9.6.1/JDK 25 AND is configuration-cache clean, which unblocks the pending gradle.properties decision
metadata:
  type: project
---

Measured on 2026-07-26 while standing up the `:cri` proto pipeline:

- protobuf-gradle-plugin 0.10.0 runs clean on Gradle 9.6.1 with the JDK 25
  toolchain, despite the plugin only claiming support up to Gradle 9.5.0.
- `./gradlew clean :cri:generateProto :cri:build --configuration-cache` succeeds
  and stores an entry — no configuration-cache problems reported.

**Why this matters:** `gradle.properties` deliberately leaves
`org.gradle.configuration-cache` disabled with a comment saying to enable it
"once `:cri:generateProto` is green", because the protobuf plugin historically
broke configuration cache. That precondition is now met and the fear did not
materialise.

**How to apply:** enabling configuration cache is a root-level change, so it was
NOT made as part of the `:cri`-scoped work. If someone asks about build speed or
about that gradle.properties TODO, this is the evidence that it can be turned on
— but re-verify before flipping it, since the wrapper and its tests are not
written yet. See [[cri-pipeline-phasing]].
