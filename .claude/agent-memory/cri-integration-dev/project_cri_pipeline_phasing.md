---
name: cri-pipeline-phasing
description: Both :cri phases are now done — proto pipeline landed 2026-07-26, the idiomatic wrapper and error translation landed in the same week. What remains is integration testing.
metadata:
  type: project
---

`:cri` was built in two deliberate changes, both now complete.

- **Phase 1 (2026-07-26)** — proto pipeline: vendored CRI proto,
  protobuf-gradle-plugin config, stub generation.
- **Phase 2 (2026-07-26)** — the idiomatic wrapper: `mcorch.cri.CriClient` and
  its own Kotlin model types, typed error translation, per-call deadlines,
  cancellation, and 95 unit tests driven against a fake CRI server over grpc's
  in-process transport.

**Why the split:** the pipeline is fiddly enough on its own that mixing it with
wrapper design makes failures ambiguous — a generation failure and a wrapper
compile failure look the same in the build log. That worked; keep the split if
the pipeline is ever rebuilt.

**How to apply:** `:cri` is no longer "unfinished". If asked to extend it, the
remaining known gaps are (a) behaviour against a real containerd, which belongs
to `integration-tester`, and (b) CRI RPCs deliberately not wrapped —
`GetContainerEvents`, stats, `Attach`, `PortForward`,
`UpdateContainerResources`, `ImageFsInfo`, `CheckpointContainer`,
`ReopenContainerLog`. Do not re-litigate proto vendoring or plugin setup; check
`cri/PROTO_SOURCE.md` and `cri/build.gradle.kts` first. See
[[cri-build-env-findings]] and [[cri-wrapper-design-decisions]].
