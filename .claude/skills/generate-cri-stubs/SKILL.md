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

**Known-good set** — verified end to end on 2026-07-26, Gradle 9.6.1 / JVM 25 toolchain. Start from this when bumping, and change one axis at a time:

| | |
|---|---|
| containerd | 2.3.3 |
| CRI proto | `kubernetes/cri-api` @ `v0.36.0`, `pkg/apis/runtime/v1/api.proto` |
| protoc / protobuf-java / protobuf-kotlin | 4.35.1 (must move together) |
| grpc-java | 1.82.1 |
| grpc-kotlin | 1.5.0 |
| protobuf-gradle-plugin | 0.10.0 |

Note `grpc-kotlin-stub` 1.5.0 declares `grpc-stub` 1.62.2 as an ordinary compile dependency. That is a *floor*, not a ceiling — Gradle resolves up to the 1.82.1 requested elsewhere, and the Kotlin stub layer rides on top of it fine. Do not hold grpc-java back to match it.

### 2. Vendor the .proto

Place the CRI `.proto` (and its imports) under `:cri`'s proto source set (`src/main/proto`). Do not fetch it at build time from an unpinned URL.

**Check the `import` lines before assuming one file is enough.** Missing imports are one of the most common generation failures, and older CRI releases pulled in gogoproto. If the release you target imports anything, vendor those files too, preserving their paths relative to `src/main/proto/`. As of `k8s.io/cri-api` v0.36.0 the v1 `api.proto` is self-contained — proto3, `package runtime.v1`, zero imports.

Record provenance in `cri/PROTO_SOURCE.md`: upstream repo, path, tag/ref and the commit it resolves to, retrieval URL, date, **the sha256 of the vendored file**, and which containerd release it corresponds to. The checksum is the point — it is what lets the next bump prove the file is byte-identical to upstream instead of assuming it. Vendor unmodified: do not add `java_package`, `java_multiple_files`, or any other option to a file you are also claiming is upstream.

### 3. Configure the protobuf plugin

In `:cri`'s `build.gradle.kts`:

- apply `com.google.protobuf`
- configure `protoc` from the pinned version
- register the `grpc` (grpc-java) and `grpckt` (grpc-kotlin) codegen plugins, and add the `kotlin` builtin alongside the default `java` one
- keep every dependency `implementation`-scoped, so downstream modules never see a generated type

Three details that cost real time if you get them wrong:

**`protoc-gen-grpc-kotlin` needs a classifier and an extension.** `protoc` and `protoc-gen-grpc-java` resolve as native executables — the plugin supplies the os-detector classifier automatically. `protoc-gen-grpc-kotlin` does not: it is a plain JAR, and the reference form is

```
io.grpc:protoc-gen-grpc-kotlin:<version>:jdk8@jar
```

`jdk8` is the only published classifier. Omitting it is the single most likely way this step fails.

**Use `create("grpc")`, not the `id("grpc")` helper.** That `id()` extension lives in the plugin's `ProtobufConfiguratorExts.kt` next to an `AndroidSourceSet` extension; loading it without the Android Gradle Plugin on the classpath is a known hazard.

**Do not add a `kotlin.srcDir(...)` for the generated output.** The plugin already wires the generated directories in through `sourceSet.java.srcDirs(output)`, and the Kotlin plugin picks them up from there. Adding one produces duplicate source roots.

Generated output lands at `build/generated/sources/proto/$sourceSet/$plugin` — for this module, `build/generated/sources/proto/main/{java,kotlin,grpc,grpckt}`. Note `sources`, plural; the pre-0.9 plugin layout was `build/generated/source/proto/...`, and stale docs still show it.

**The plugin creates a generate task per source set, including ones you add for other reasons.** `:cri` has an `integrationTest` source set for tests against a real containerd, so the build also carries `generateIntegrationTestProto` and `processIntegrationTestProtoResources`. There is no `src/integrationTest/proto`, so both are no-ops — but they exist, they run on a clean build, and they are not evidence that something generates stubs twice. The stubs come from `main` and only from `main`; a source set that needs them gets them from `sourceSets["main"].output`, never from its own generation.

Reference the pinned versions through the version catalog rather than inlining version strings. The protobuf plugin wants coordinate *strings*, not `Dependency` objects, so rendering a catalog `Provider` into `group:name:version` with a small local helper is preferable to hardcoding.

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
| `protoc-gen-grpc-kotlin` will not resolve | Missing the `:jdk8@jar` classifier and extension. See step 3 |
| Proto compiles alone but not in the build | An `import` in the vendored proto was not vendored alongside it. See step 2 |
| Downstream module imports generated types directly | Source sets are wired too broadly. Generated types stay inside `:cri`; expose only the wrapper |
| Duplicate source root / same class generated twice | A redundant `kotlin.srcDir(...)` was added on top of the plugin's own wiring. Remove it |
| Clean build fails but incremental passes | Something depends on stale generated output. Always verify with `./gradlew clean` |
| ktlint/spotless tries to format generated sources | Exclude `build/generated` from formatting (the format hook already skips it) |

Two failures the pipeline is *expected* to hit but currently does not. Both depend entirely on codegen versions, so recheck them after any bump rather than assuming they are permanently solved — or permanently a problem:

| Symptom | Cause and current status |
|---|---|
| Generated Kotlin fails explicit API mode ("Visibility must be specified") | Does **not** occur at protoc 4.35.1 / grpc-kotlin 1.5.0 — both emit explicit `public` and explicit return types, so generated code compiles under `explicitApi()` with no exemption. If a bump reintroduces it, exempt only the generated compilation. Never weaken `explicitApi()` for the whole project to fix a `:cri` problem |
| Generated Java fails on `@javax.annotation.Generated` | Does **not** occur at grpc-java 1.82.1 — it emits `@io.grpc.stub.annotations.GrpcGenerated` instead, which needs nothing on the classpath. If a bump reintroduces it, add `jakarta.annotation-api` or `org.apache.tomcat:annotations-api` to `:cri` |

## Definition of done

- Versions pinned in `libs.versions.toml`, proto vendored with provenance recorded in `cri/PROTO_SOURCE.md` including its sha256
- `./gradlew clean :cri:generateProto :cri:build` passes, and so does a whole-project `./gradlew clean build`
- Downstream modules use only the `:cri` wrapper, never generated types. Prove it from the consumer side — `./gradlew :core:dependencies --configuration compileClasspath` should show no grpc, protobuf, or netty entries. The `implementation` keyword on the producer is the mechanism, not the evidence
- No generated source is committed as editable source or hand-modified
