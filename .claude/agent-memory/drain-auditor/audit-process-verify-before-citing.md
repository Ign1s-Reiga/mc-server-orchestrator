---
name: audit-process-verify-before-citing
description: Re-read the file at the commit under audit before quoting a line; a reading from an earlier round is a claim about an earlier round
metadata:
  type: feedback
---

Before writing "X still does Y" in a finding, re-read X **at the commit under
audit**. A reading from a previous round is a statement about that round.

**Why:** round 53 reported a critical — *"`recordStopDispatched` is still
`store.putStatus(next)` with no `Precondition`"* — that was false. The
precondition had landed in an intervening commit (`08f62fb`) that was not part
of the diff I was pointed at. I had read that function two rounds earlier, at a
different commit, and cited that reading as current. Compounding it: the commit
I *was* auditing contained a paragraph rebutting "a precondition", about a
different write, and I read it as a rebuttal of my own recommendation. Two
plausible signals agreeing made not checking feel safe.

**How to apply:**
- A finding of the form "still", "not yet", "was not taken" is a claim about the
  present. Open the file at the audited ref and confirm the line before shipping
  it. `git show <ref>:<path>` or reading the working tree at that ref, not memory.
- When the diff under review argues *against* something you recommended, check
  whether it is arguing about the same object. A rebuttal of a neighbouring
  change reads identically to a rebuttal of yours.
- The intervening commits between two audits are part of the audit surface.
  `git log <last-audited>..<now>` before starting, not just the commit named.
- Being wrong about a fact costs more than missing a finding: it spends the
  reviewer's credibility on the findings that *are* right, and here it nearly
  buried a real one underneath it.

Related: [[drain-audit-danger-patterns]]
