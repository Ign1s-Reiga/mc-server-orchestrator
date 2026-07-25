---
name: verify-build-comments
description: Build files in this repo carry confident explanatory comments that sometimes overstate what the build does — verify empirically
metadata:
  type: feedback
---

This repo's build files and version catalog are heavily commented with rationale ("epoll is
needed, so we use non-shaded grpc-netty", "this is `implementation` so it cannot leak"). Some
of those comments describe an *intent* that the surrounding config does not actually implement.
Verify the claim, do not review the comment.

**Why:** In the first scaffold review, a catalog comment justified a dependency choice by a
need (native epoll for the containerd Unix socket) whose artifact was absent from every
resolved classpath. Reading only the comment would have passed it.

**How to apply:** For any load-bearing claim in a Gradle comment, probe it:
`./gradlew :<mod>:dependencies --configuration runtimeClasspath` for "this dep is/isn't here";
a throwaway project under the scratchpad with `./gradlew -p <dir>` for "does this version
resolve"; an init script reading `compilerOptions.freeCompilerArgs` for "is this compiler flag
really applied to that compilation". These are read-only and cheap. Also check enforcement
claims from the *consumer* side — `:app`'s compile classpath is what proves a boundary holds,
not the producer's `implementation` keyword.

See [[review-output-style]].
