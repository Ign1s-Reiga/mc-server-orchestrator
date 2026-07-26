# Agent Memory — cri-integration-dev

- [CRI pipeline phasing](project_cri_pipeline_phasing.md) — both `:cri` phases are done; what is left is integration testing and deliberately-unwrapped RPCs.
- [CRI wrapper design decisions](project_cri_wrapper_design_decisions.md) — why UNKNOWN is retryable, why StopGracePeriod has no default, and the JUnit non-Unit test trap.
- [CRI build environment findings](project_cri_build_env_findings.md) — protobuf plugin 0.10.0 is fine on Gradle 9.6.1 and is configuration-cache clean.
