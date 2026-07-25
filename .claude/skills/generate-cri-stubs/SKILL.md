---
name: generate-cri-stubs
description: The procedure for generating the CRI gRPC stubs in :cri from the CRI .proto using the protobuf Gradle plugin and grpc-kotlin. Use this skill whenever setting up or changing the proto pipeline, bumping the CRI/containerd version, regenerating stubs, or debugging why generated CRI sources will not compile — even if nobody says "proto". Getting this pipeline right once is what keeps the project a single Kotlin build; skipping the setup steps produces confusing generation failures.
---

# Generating CRI stubs

The orchestrator talks to containerd over CRI. There is no official Java client, so `:cri` generates its own stubs from the CRI `.proto`. This is the one place non-Kotlin artifacts enter the build, and it is fiddly, so follow these steps rather than improvising.

## Principle

Generated stubs are build output. They live under `build/generated/…`, are never committed as hand-editable source, and are never hand-formatted or hand-edited. If the generated surface is awkward, wrap it in `:cri` — do not touch the generated file.

## Setup (first time, or when changing the pipeline)

### 1. Pin versions first

Before writing any build config, have `docs-researcher` confirm, for the containerd version you target:

- the matching CRI `.proto` (the `k8s.io/cri-api` runtime service proto for that release)
- compatible versions of `protobuf-gradle-plugin`, `protoc`, `grpc-java`, and `grpc-kotlin`

Pin all of them in `gradle/libs.versions.toml`. CRI proto fields differ across releases; a mismatched proto is the most common cause of "it generates but does not behave".

### 2. Vendor the .proto

Place the CRI `.proto` (and its imports) under `:cri`'s proto source set (`src/main/proto`). Record where it came from and its version in a comment or a small `PROTO_SOURCE.md`, so the next bump knows the provenance. Do not fetch it at build time from an unpinned URL.

### 3. Configure the protobuf plugin

In `:cri`'s `build.gradle.kts`:

- apply `com.google.protobuf`
- configure `protoc` from the pinned version
- add the `grpc` (grpc-java) and `grpckt` (grpc-kotlin) plugins to the generation
- ensure the generated source sets are wired so `:cri` compiles them, and so downstream modules never see them directly

### 4. Generate and verify

```bash
./gradlew :cri:generateProto
./gradlew :cri:build
```

Both must pass from a clean checkout (`./gradlew clean` first) — generation must not depend on state left by a previous run.

## Regenerating (routine)

After changing the vendored proto or bumping a pinned version:

```bash
./gradlew clean :cri:generateProto :cri:build
```

Confirm the wrapper in `:cri` still compiles against the regenerated surface. If the CRI service changed method signatures, the wrapper is where you absorb that — update it in the same change.

## Common failures

| Symptom | Cause |
|---|---|
| Generation succeeds, calls fail at runtime with unknown field | Proto version does not match the targeted containerd. Re-pin per step 1 |
| Generated Kotlin does not compile | grpc-kotlin / grpc-java / protoc versions are mismatched. Align them in libs.versions.toml |
| Downstream module imports generated types directly | Source sets are wired too broadly. Generated types stay inside `:cri`; expose only the wrapper |
| Clean build fails but incremental passes | Something depends on stale generated output. Always verify with `./gradlew clean` |
| ktlint/spotless tries to format generated sources | Exclude `build/generated` from formatting (the format hook already skips it) |

## Definition of done

- Versions pinned in `libs.versions.toml`, proto vendored with recorded provenance
- `./gradlew clean :cri:generateProto :cri:build` passes
- Downstream modules use only the `:cri` wrapper, never generated types
- No generated source is committed as editable source or hand-modified
