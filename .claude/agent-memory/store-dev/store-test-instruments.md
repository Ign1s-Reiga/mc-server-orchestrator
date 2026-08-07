---
name: store-test-instruments
description: The standing instruments :store carries against whole classes of defect, what each does and does not catch, and the constraints that shaped how they enumerate
metadata:
  type: project
---

Tests in `:store` that guard a *class* of mistake rather than one behaviour. Each was
built after a specific audit finding, and each has a stated blind spot — the blind
spot is the part worth remembering, because it is where the next finding lands.

**`LegacyDrainRowTest` — every persisted optional field must declare what a row
without it reads as.** Built for audit 37's fourth item.
**Why:** `DrainStatus.stopDispatchedAt` was added inside the status document and
nothing reddened, because a round trip only ever sees documents *this* build wrote,
and this build writes the key. The uncovered population is rows that predate the
field, and no fixture had one.
**How to apply:** it enumerates properties of `DrainStatus`/`DrainBlock`/
`FailureStatus`, drops each key from an encoded document and requires a declared
answer — `Reads` (the record the decode produces plus the reconstruction it reports)
or `Refused` (the row is rejected, not defaulted). Both labels are *verified against
the codec*, so a `Refused` field that becomes droppable fails. Adding a persisted
optional field means adding an entry; there is no way to skip it.

Three constraints that shaped it, all still true:
- **Enumeration is `toString()` parsing, not reflection.** `kotlin-reflect` is on
  `:store`'s test classpath only *transitively* and at 2.2.21 against a 2.4.10
  compiler, and the matching version is not in the offline cache. An instrument built
  on it is one a kotest bump could silently remove. A data class `toString` lists
  every constructor property and grows when one is added, which is all that is needed.
  Parse at bracket depth zero and keep probe values free of `,` `=` and brackets.
- **The probe is a document, not a claim about the orchestrator.** It sets every
  optional field at once including pairs a real drain never holds, which is only safe
  because a companion test asserts the untouched probe round-trips unchanged. That
  test is what stops a future read-side rule being blamed on a missing key — see
  [[feedback-review-standards]] on impossible fixtures.
- **Scoped to the drain record deliberately.** Its fields are records of side effects
  that already left the process, so absence is a claim about the past. A status type's
  optional fields are observations, where absence honestly means "not observed".

**What it cannot catch, by construction.** The audit said this out loud and it is the
reason the instrument is only half the answer: it keys on a **field's absence**. The
round-37 defect keyed on a **state**, and the failure was that only one of that
state's two producers was surveyed. The companion — a scan over `moveTo(DrainState.X)`
producers for any `X` a decode rule keys on — belongs in `:core` and was routed to
`reconciler-dev`. Do not try to build it here; `:store` cannot see the producers.

See [[store-design-decisions]] for the reconstruction rule itself and
[[feedback-review-standards]] for the mutation discipline every one of these went
through.
