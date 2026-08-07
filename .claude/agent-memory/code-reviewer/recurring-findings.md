---
name: recurring-findings
description: Failure shapes that keep recurring in this repo's reviews — check these first on any new module
metadata:
  type: feedback
---

Across audits of this repo the same five shapes keep coming back. Check them before
reading anything else in a new or changed module.

1. **A control/desired-state operation with no deadline can wedge permanently.**
   This codebase models long operations as "start-or-join": a repeat request joins the
   in-flight one rather than starting a second, gated on a `finishedAt` field. Every such
   gate needs a way to *become* finished when the underlying future never completes or an
   issuing loop aborts partway. Look for: a tally compared against a count captured at
   start, a `whenComplete` that is the only writer of that tally, and no `orTimeout` /
   staleness check anywhere.

2. **Comments in build files and version catalogs overstate what the config does.**
   See [[verify-build-comments]]. Latest instance: a `verifyPluginJar` doc comment named
   six proxy-provided libraries it keeps out of the fat JAR; the task actually checked
   four of the six prefixes. Always diff the prose list against the code list.

3. **Secrets are protected on one type and not its sibling.** A hand-written `toString`
   override guarding a token, next to a `data class` holding the same token verbatim with
   a compiler-generated `toString`/`equals`/`copy`. The guarded type gets a test; the data
   class does not.

4. **Tests assert side effects well and concurrency not at all.** The suites here are
   unusually good at reading state off a fake instead of off a returned status, and
   unusually good at including a "control" assertion that proves the search had something
   to find. What they do not have is any test with two threads. When a review brief asks
   about locks, the answer is almost always "the suite cannot tell you".

5. **Hand-rolled parsers lean on Kotlin stdlib helpers that are more permissive than the
   grammar.** `Char.isDigit()` / `Char.isWhitespace()` are Unicode-wide, and
   `toIntOrNull(radix)` accepts a leading `+`/`-`. Modules that document their parser as
   "deliberately strict" have all three.

**Why:** These are the findings that survived to the "fix before merge" bucket in more
than one review. The user writes very high-quality prose around the code, which makes
lapses harder to spot by reading — the prose describes the intended invariant even where
the code misses it.

**How to apply:** Read the code against the doc comment rather than with it. When a
comment enumerates a list (libraries excluded, versions supported, fields redacted),
check the enumeration item by item against what executes.

See [[review-output-style]] and [[user-profile]].
