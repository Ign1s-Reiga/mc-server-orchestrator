---
name: review-output-style
description: How the user wants code review output on this repo — read-only, file:line, severity-bucketed, line-budgeted
metadata:
  type: feedback
---

Reviews here are strictly read-only, bucketed by severity, every finding anchored to
`file:line`, and kept inside an explicit line budget. Do not list things that are fine.

**Why:** The user reviews the findings themselves and applies fixes by hand; an agent editing
during review destroys the diff they are reading. The line budget is because they want the
ranking to do the work — a long list with everything in it is the same as no ranking.

**How to apply:** When asked to review, never edit, even to fix something trivial. Lead with
the single most consequential finding. Prefer "here is the one-line fix" over prose. If a
direct question in the brief has a clean answer ("is explicit API actually enforced?"), a
one-line confirmation is welcome even though "do not list what is fine" otherwise applies —
they asked, so answer.

See [[user-profile]].
