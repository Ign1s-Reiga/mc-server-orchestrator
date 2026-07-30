---
name: unreadable-state-posture
description: How :core treats stored state it cannot decode — which half costs the fleet, which half is already safe via getServer, and why the loop's skip is defence in depth rather than the fix
metadata:
  type: project
---

`:store` distinguishes the two halves of a row it cannot decode, and `:core` has
to treat them differently.

**An unreadable *definition* used to cost the whole fleet.** `resync` and
`resumeDrains` called the strict reads, which raise for such a row, so one bad
row meant nothing was ever queued — every pass, indefinitely. Now they call
`Store.listAll` / `listAllByDrainState`, which return those rows as entries
instead of as a failure. This was the reachable bug and it is pinned by a test
that times out against the strict reads.

**An unreadable *observation* was already safe, and not by anything in `:core`.**
`Store.getServer` refuses that row rather than returning a `StoredServer` with a
null status, so a pass never mistakes "cannot read" for "nothing observed" and a
mid-flight drain cannot restart and re-issue its save. I was asked to fix this as
though it were live; it was not, and the drain test was green the first time it
ran.

**Why:** the protection lives in another module's *point-read contract*. Nothing
in `:core` expresses it, and a tolerant point read introduced later would remove
it silently. `StoredServer.neverObserved` exists precisely so a caller cannot
conflate the two — reach for it over `status == null` if `:core` ever reads
tolerantly by name.

**How to apply:** when told a hazard is live, check the path before fixing it.
Both my tests here are guards on properties owned elsewhere, not regressions —
the drain one was green before the change and the loop-skip one still passes with
the skip removed. Both say so in their docstrings. Keeping a guard is right;
letting a reader think it caught something is not. See
[[prove-the-test-can-fail]].

The loop's skip of an unreadable observation therefore buys **reporting**, not
safety: one clear error per resync naming the server, instead of a generic store
failure per pass, and not leaning on a refusal that happens in another module.
Do not oversell it if it comes up in review.
