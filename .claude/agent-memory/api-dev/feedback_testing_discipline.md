---
name: repo-testing-discipline
description: This repo's tests want real dependencies and control assertions that prove the test could fail — copy :store's SecretLeakageTest shape
metadata:
  type: feedback
---

Write tests against real dependencies, and pair every "X is absent" assertion
with a control proving the search could have found X.

**Why:** the request for `:api` named `:store`'s `SecretLeakageTest` as "the
shape to copy, **including its control assertion so the search can actually
fail**". That test asserts material is *not* in `state.db` and then asserts it
*is* in `secrets.db` — without the second half the first passes the day the
needle stops being findable. The same instinct runs through the repo: `:api`'s
tests bind a real port and open a real `EmbeddedStore` in a temp directory
rather than mocking a layer, because most of what is worth checking lives
between the layers.

**How to apply:**

- For a leakage test, assert three controls: the material really is stored, the
  responses really do describe the thing (its coordinates come back), and the
  matcher really can find a needle in a haystack of that size.
- Prefer a structural assertion where one exists. `RouteTableTest` asserts on the
  route table, so a `PUBLIC` mutating route fails at registration rather than
  when somebody notices. A classpath assertion that `:cri` and `:core` types are
  unloadable is stronger than any number of behavioural tests that no handler
  calls them.
- Generate credentials per run; never write a literal one in a test file.
- When a streaming test needs two things to happen in order, use a latch on the
  event that proves the first landed. Sleeps are the thing that makes a suite
  flaky later. A delete and a purge inside one poll interval legitimately
  coalesce — that is the stream working, and a test that does not sync on the
  intermediate state is testing nothing.

Related: [[api-module-decisions]].
