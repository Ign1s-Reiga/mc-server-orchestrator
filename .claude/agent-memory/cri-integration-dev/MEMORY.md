# Agent Memory — cri-integration-dev

- [CRI pipeline phasing](project_cri_pipeline_phasing.md) — both `:cri` phases are done; what is left is integration testing and deliberately-unwrapped RPCs.
- [CRI wrapper design decisions](project_cri_wrapper_design_decisions.md) — why UNKNOWN is retryable, why StopGracePeriod has no default, and the JUnit non-Unit test trap.
- [CRI build environment findings](project_cri_build_env_findings.md) — protobuf plugin 0.10.0 is fine on Gradle 9.6.1 and is configuration-cache clean.
- [The runBlocking loop trap](project_runblocking_loop_trap.md) — the "integration stall" was a passing test parked on the loop it launched; how to tell a real hang from a quiet one.
- [ExecSync timeout attribution](project_cri_exec_timeout_attribution.md) — containerd reports a command timeout with the same code as our transport deadline; how `commandTimeout` separates them.
- [CRI log redaction policy](project_cri_log_redaction_policy.md) — why three operations never log the runtime's error text; do not "restore the useful detail".
