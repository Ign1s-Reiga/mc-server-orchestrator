---
name: cri-pipeline-phasing
description: The :cri module is being built in two deliberate phases — proto pipeline first (done 2026-07-26), idiomatic wrapper and error translation later
metadata:
  type: project
---

`:cri` is deliberately split into two changes. Phase 1 (proto pipeline: vendored
CRI proto, protobuf-gradle-plugin config, stub generation) landed 2026-07-26 on
branch `feat/gradle-multi-module-scaffold`. Phase 2 (idiomatic client wrapper,
typed error translation from `StatusRuntimeException`, sandbox/container/image
lifecycle operations, timeouts and cancellation, fake-gRPC-server tests) was
explicitly scoped OUT of phase 1 and comes later.

**Why:** the pipeline is fiddly enough on its own that mixing it with wrapper
design makes failures ambiguous — a generation failure and a wrapper compile
failure look the same in the build log.

**How to apply:** if asked to "finish `:cri`", the remaining work is the wrapper,
not the build config. Do not re-litigate proto vendoring or plugin setup; check
`cri/PROTO_SOURCE.md` and `cri/build.gradle.kts` first, which record what was
already settled and verified. See [[cri-build-env-findings]].
