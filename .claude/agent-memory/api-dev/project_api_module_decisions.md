---
name: api-module-decisions
description: The load-bearing choices behind :api — no HTTP framework, no JSON parser, no :core edge — and what would have to change to revisit each
metadata:
  type: project
---

`:api` was built from zero sources on 2026-07-30. Four decisions shape everything
in it, and each was made against a specific alternative rather than by default.

**No HTTP framework.** `com.sun.net.httpserver` plus hand-written routing, CORS,
auth and SSE framing. Ktor or http4k would each have added 8–12 artifacts (Ktor
additionally a Kotlin compiler plugin whose version tracks the Kotlin version)
for a dozen endpoints and one stream.

**Why:** this repo justifies every dependency in a paragraph
(`gradle/libs.versions.toml` picks `slf4j-simple` because it has no transitive
deps), and its pitch is "depends on nothing but containerd". JVM 25 virtual
threads make thread-per-exchange the *right* shape for long-lived SSE, not a
compromise.

**How to apply:** the cost is paid in `api/src/main/kotlin/mcorch/api/http/`. If
that directory starts growing features nobody asked for — content negotiation,
a template engine, middleware chains — that is the signal to revisit, not the
endpoint count.

**No JSON parser, anywhere.** YAML 1.2 is a strict superset of JSON, so a
browser's `JSON.stringify(definition)` goes through `ServerDefinitionParser` and
comes back with the same field paths *and* line/column positions into the JSON
the client sent. Secret material is read as raw bytes so it is never bound into
an intermediate `String`. Auth uses a header, not a body.

**How to apply:** before adding a JSON dependency, check whether the new body is
really a third shape. Two of the three bodies are already covered by things that
are not parsers.

**No `:core` edge, and it was checked rather than assumed.** Every mutation is a
desired-state write: `Reconciler.drainCause` returns `DELETION` for a terminating
definition and `REPLACEMENT` for a changed spec, so `DELETE` and `PUT` *are* the
drain triggers. `purge` is not exposed because `:core` owns the guard that it
runs only once containers are gone.

**How to apply:** if a future request seems to need `:core`, first check whether
it can be expressed as a field the loop converges on. Log streaming genuinely
cannot (it needs the `Node` abstraction) and is the most likely first real case.

**No OpenAPI.** The contract is `api/API.md`, hand-written.

**Why:** what a client must get right here — why delete answers 202, what
TERMINATING obliges the UI to show, which of two null policies applies where —
is not expressible in a schema. The TypeScript block at the end is the
machine-readable part.

**How to apply:** treat `API.md` as the specification and the Kotlin as one
implementation. If they disagree, say which is the bug.

**Validated in practice.** A Next.js dashboard was built against `API.md` and
came out with zero `any` and zero casts, drove a real Paper server to `READY`,
and used the derived `display` badge as-is rather than recomputing it. The six
findings it returned were all *contract* gaps, not implementation bugs: a
declared-but-never-emitted enum variant, a loosely typed enum, an undocumented
`retry:`, two closed sets missing from `/meta`, an over-broad promise in §10,
and — the big one — §14 declaring the *output* type as the input type when the
two genuinely differ. That is the failure mode to look for on a contract
change: not "is it wrong" but "does it declare more, or less, than is true".

**How to apply:** when adding to §14, ask separately what a client *sends* and
what it *receives*. They are different types here and were conflated once
already.

Related: [[api-contract-subtleties]], [[repo-testing-discipline]].
