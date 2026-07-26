# Memory

- [Standalone drain decision](project_standalone-drain-decision.md) — the human's ruling on no-proxy drains, plus my follow-on calls that are still open to overruling.
- [Paper image contract](project_unverified-paper-image-contract.md) — the in-container commands, now verified, and the 60–95s startup window that makes probes time out normally.
- [Assert on side effects](feedback_assert-on-side-effects.md) — how tests here go green while proving nothing, and the habits that prevent it.
- [Drain against the container](feedback_drain-against-the-container.md) — save evidence expires, and a drain reads the workload's labels rather than the edited definition.
- [Escalation ruling](project_escalation-ruling.md) — how far item 7 reaches: escalate the report of a stuck drain, never the container's fate.
- [Integration freeze that never was](project_integration-freeze.md) — runBlocking parented the loop, so only *passing* tests hung. Resolved; keep for the diagnosis method.
- [LocalNode test gap](project_localnode-test-gap.md) — collapse a decision into the module that owns it; a raw value plus a flag about it means the seam is wrong.
- [Classify narrowly, contain broadly](feedback_classify-narrowly-and-contain-broadly.md) — keep the never-retry bucket small; let no exception escape a worker.
- [Audit remedies are hypotheses](feedback_audit-remedies-are-hypotheses.md) — the finding is established, the prescribed helper may itself be wrong; the suite arbitrates.
- [Prove the test can fail](feedback_prove-the-test-can-fail.md) — `--rerun` or it never ran; virtual time hides races; a control assertion can be unfindable.
