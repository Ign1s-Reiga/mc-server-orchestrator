# Agent Memory — cri-integration-dev

- [CRI pipeline phasing](project_cri_pipeline_phasing.md) — both `:cri` phases are done; what is left is integration testing and deliberately-unwrapped RPCs.
- [CRI wrapper design decisions](project_cri_wrapper_design_decisions.md) — why UNKNOWN is retryable, why StopGracePeriod has no default, and the JUnit non-Unit test trap.
- [CRI build environment findings](project_cri_build_env_findings.md) — protobuf plugin 0.10.0 is fine on Gradle 9.6.1 and is configuration-cache clean.
- [The runBlocking loop trap](project_runblocking_loop_trap.md) — the "integration stall" was a passing test parked on the loop it launched; how to tell a real hang from a quiet one.
- [ExecSync timeout attribution](project_cri_exec_timeout_attribution.md) — containerd reports a command timeout with the same code as our transport deadline; how `commandTimeout` separates them.
- [CRI log redaction policy](project_cri_log_redaction_policy.md) — why three operations never log the runtime's error text; do not "restore the useful detail".
- [StopContainer timeout overflow](project_cri_stop_timeout_overflow.md) — past 9223372036s containerd kills at once and reports success; why crictl cannot find the boundary.
- [The capped stop deadline](project_cri_stop_deadline_cap.md) — the deadline is not the grace; containerd never escalates to SIGKILL once our context expired.
- [Guard symmetry rule](feedback_guard_symmetry.md) — an outer layer asks the type that owns a rule; it never restates a weaker version of it.
- [The :cri integration source set](project_cri_integration_sourceset.md) — what belongs there, and the shared-containerd hazard when other agents' worktrees are running.
- [Reading a deadline experiment](project_cri_deadline_evidence.md) — grpc's "deadline exceeded after Xs" is not the elapsed time, and a shrunken deadline must not judge the control step.
