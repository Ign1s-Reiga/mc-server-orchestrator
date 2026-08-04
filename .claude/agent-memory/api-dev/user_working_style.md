---
name: user-working-style
description: The user wants reasoned decisions with the alternative named, a contract as a deliverable in its own right, and things deliberately left out to be said out loud
metadata:
  type: user
---

Works on this orchestrator as its architect: hands over a module with the
surrounding modules already finished and verified, states the invariants, and
leaves the design decisions genuinely open.

What the briefs consistently ask for:

- **The decision *and* the alternative it was made against.** "The framework you
  chose and why", "the auth threat model", "whether you needed a `:core` edge".
  A choice reported without its rejected alternative is not an answer.
- **A contract as a first-class deliverable**, not a by-product. For `:api`:
  "I would rather hand over your specification than have someone infer it from
  Kotlin."
- **What was deliberately left out, said explicitly.** Silence about a gap reads
  as an oversight; a named omission with its reason reads as a decision.
- **Pushback where the brief is wrong.** `api/build.gradle.kts` said to keep the
  module free of `:core` and added: "If you conclude you need it, say why rather
  than just adding it."

Also: comments in this codebase carry the *reasoning*, often at length, including
past incidents ("two separate bugs came from a site clearing it without
consulting the flag"). Matching that register is expected — a one-line comment
where the repo would have written a paragraph reads as under-explained.

Commits are Conventional Commits, one per logical change, with a body that
explains why. The branch is signed and gpg works.

**Standing instruction: branch from the integrated tip, never merge it in.** My
work is integrated onto the shared branch as rebased copies (same messages, new
SHAs), so a merge finds a merge base hundreds of commits back and reports every
shared file as an add/add conflict — 31 of them across four modules on one
occasion. `git checkout -b <type>/<abstract> <integrated-branch>` is the way to
start a round.

Related: [[repo-testing-discipline]].
