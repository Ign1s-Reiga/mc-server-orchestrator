---
name: queued-findings-need-their-own-fact
description: When findings are parked behind "a new status field", check each one's fact against the field actually added — closing one that is not closed is worse than leaving it open
metadata:
  type: feedback
---

A finding recorded as *"waiting for a field on X"* is a claim by whoever wrote
it that their fact and that field are the same fact. Verify it per finding
before reporting anything closed, and report the ones that do not close as
still open with the shape they actually need.

**Why:** the human asked for this in as many words on 2026-08-08 — *"if one of
them turns out to need something else, say so rather than declaring it closed; a
finding marked closed by a change that does not close it is worse than one left
open"* — and the instance proved them right. Two findings were parked behind
`ControlEndpointStatus`'s missing credential field, and the note in
`ControlChannel.unbuildable` said in so many words that they needed *"the same
field"*. They did not:

- **"Answering, but not to us"** (a rotated control token) is a verdict about an
  *authenticated call that was made*. `credential: ControlCredential` closes it.
- **`readControl` discards the detail** is about calls that were **never sent**,
  or that were answered unreadably: an `EndpointRequest` this build cannot
  construct from the proxy's own `spec.control.port` / `sealTimeout`, a body that
  will not parse, an error code this build does not know. All three land in
  `ControlOutcome.Unavailable` and are flattened to `reachable = false`. No
  credential verdict says anything about them; what that one needs is a *reason
  for the non-contact*, and the enum has to distinguish "never attempted" from
  "no answer" from "answered unreadably" — the last of which is the same branch
  claiming `reachable = false` about an endpoint that demonstrably answered.

The same session's other half is the mirror image and the reason to read the
memory file rather than a summary: the human's own brief described one item's
direction backwards, having been read from a compressed note rather than the
code.

**How to apply:** when a brief says "two findings are queued behind this field",
find each finding's own record (`.claude/agent-memory/*/`, and the KDoc at the
site — they disagree), state the *fact* each one needs in one sentence, and hold
the proposed field against those sentences. If a finding needs a different
field, do not add it in the same change on your own judgement: name its shape,
say what it costs, and hand the decision back. See
[[schema-violation-message-rules]] for why the readControl one wants an enum and
not the `detail` string it already has — a malformed-body detail quotes the body.
