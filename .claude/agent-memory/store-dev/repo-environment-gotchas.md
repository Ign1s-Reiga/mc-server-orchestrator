---
name: repo-environment-gotchas
description: Build and tooling facts for this repo that cost time to rediscover — offline Gradle, unsigned agent commits, and a NUL-byte hazard when writing unicode escapes through the file tools
metadata:
  type: project
---

**Gradle works offline.** `./gradlew build --offline` succeeds from a warm cache. Use `--offline` by default; it is much faster and network is not guaranteed.
**How to apply:** adding a *new* artifact to `gradle/libs.versions.toml` is the risky move, not bumping code. `slf4j-api`, `sqlite-jdbc`, `kotest`, `kotlinx-coroutines-test` were all already cached.

**Agent commits in this repo are unsigned.** The user's own commits are GPG-signed (`%G?` = `G`), but signing from an agent shell times out on pinentry. Every prior agent commit is `N`.
**How to apply:** commit with `--no-gpg-sign`. Do not touch the user's global signing config.

**Writing `\u0000` through the file tools produces a raw NUL byte — in any file, not just Kotlin.**
**Why:** the escape gets unescaped somewhere before it reaches disk, so `'\u0000'` lands as `'<NUL>'`. Kotlin still compiles, Markdown still renders, but `git` then classifies the file as binary and every later diff of it is unreadable. This memory file itself was corrupted that way from the moment it was written, inside the sentence warning about the hazard, and only a reviewer reading the raw bytes caught it.
**How to apply:** after writing any file containing a unicode escape, verify with a byte-level scan (`python3` reading `read_bytes()` and counting `b"\x00"`), not with `grep` — `grep` treats the file as binary and silently matches nothing. Emitting *and* repairing the escape both work the same way: build it in a Python helper as `chr(92) + "u0000"` and write bytes.

**Example YAMLs are shared through `:schema`'s test fixtures, and `:store` must never re-spell their path.** `:schema` applies `java-test-fixtures`; the examples live in `schema/src/testFixtures/resources/examples` and `:store` takes `testImplementation(testFixtures(project(":schema")))`. `mcorch.schema.fixtures.ExampleDefinitions` owns the resource path — call `valid("full.yaml")`, never `getResource("/examples/...")`.
**Why:** the earlier arrangement pointed `:store`'s test resources at `../schema/src/test/resources`. Reproduced 2026-07-28: renaming that `examples/` directory and fixing only `:schema`'s loader left `:schema` 62/62 green and broke 97 of `:store`'s 135, because `processTestResources` copies whatever the directory holds without complaint.
**How to apply:** Gradle hands the fixtures over as `schema-test-fixtures.jar` in *both* modules, so anything enumerating them cannot assume a `file:` URL. Verified after the move: `--configuration-cache` and `-Dorg.gradle.unsafe.isolated-projects=true` both still store an entry, and no fixture reaches any consumer's compile or runtime classpath.
