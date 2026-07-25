# CRI proto provenance

The `.proto` under `src/main/proto/` is vendored, not fetched at build time. This
file records exactly where it came from so the next bump is reproducible. See the
`generate-cri-stubs` skill for the procedure.

## Vendored file

| | |
|---|---|
| Vendored at | `cri/src/main/proto/runtime/v1/api.proto` |
| Upstream repo | `github.com/kubernetes/cri-api` |
| Upstream path | `pkg/apis/runtime/v1/api.proto` |
| Ref | tag `v0.36.0` (annotated tag `bb308605818369bd7b49f442242a456a52c1ff1e` -> commit `b569fd7e8091a45af0adc2938152be8124f2948d`) |
| Retrieval URL | <https://raw.githubusercontent.com/kubernetes/cri-api/v0.36.0/pkg/apis/runtime/v1/api.proto> |
| Retrieved on | 2026-07-26 |
| SHA-256 | `e545595feb7d82c5433a443975c15f59ce5b04bb876ab30fb9c59c8eca4715c9` |
| Lines | 2279 |

The file is byte-identical to upstream. It is not edited — no `java_package`,
`java_multiple_files`, or any other option was added.

## Which containerd this corresponds to

We target **containerd 2.3.3**. Its `go.mod` pins `k8s.io/cri-api v0.36.0`, so
`kubernetes/cri-api` @ `v0.36.0` is the proto containerd 2.3.3 actually serves.
Both versions are pinned in `gradle/libs.versions.toml` as `containerd` and
`criApi` (provenance markers, not Gradle dependencies).

## Imports

`api.proto` is **self-contained**: `proto3`, `package runtime.v1`, zero `import`
statements. No gogoproto, no well-known types, nothing else to vendor. Older CRI
releases did depend on gogoproto — if a future bump reintroduces imports, vendor
them under the same source set alongside this file, preserving their import paths
relative to `src/main/proto/`.

## Generated surface

`package runtime.v1` with no `java_package` option, so protoc derives everything
from the file name:

- `runtime.v1.Api` — outer Java class, all messages/enums nested inside it
- `runtime.v1.*Kt` — protobuf Kotlin DSL builders
- `runtime.v1.RuntimeServiceGrpc`, `runtime.v1.ImageServiceGrpc` — grpc-java stubs
- `runtime.v1.RuntimeServiceGrpcKt`, `runtime.v1.ImageServiceGrpcKt` — grpc-kotlin
  coroutine stubs (`ApiGrpcKt.kt`)

Two services: `RuntimeService` (sandbox + container lifecycle, exec/attach,
stats, status) and `ImageService` (pull/list/status/remove/fs-info).

## Re-vendoring

```bash
curl -sSL -o cri/src/main/proto/runtime/v1/api.proto \
  https://raw.githubusercontent.com/kubernetes/cri-api/vX.Y.Z/pkg/apis/runtime/v1/api.proto
sha256sum cri/src/main/proto/runtime/v1/api.proto     # record it above
./gradlew clean :cri:generateProto :cri:build
```

Update this file and the `containerd` / `criApi` entries in the version catalog in
the same change. Check the new file's `import` lines before assuming it is still
self-contained.
