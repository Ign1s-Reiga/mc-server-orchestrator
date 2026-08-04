---
name: blocked-is-not-failed
description: The human's ruling that a drain blocked on players is not a failure — what carries it now, the three states a consumer must distinguish, and what stayed put
metadata:
  type: project
---

The human decided (2026-08-04, `feat/velocity-proxy-kind`) that a drain blocked
because players are still online is **the protocol working**, so it must leave
`DrainStatus.failure` null. Not to be re-derived.

**Why:** as a `FailureReason` it made every "is anything wrong here" surface say
yes about a server people were happily playing on, and it forced the escalation
to carry a named exemption — which is the mechanism that produced two separate
audit findings. Deleting the exemption was judged worth more than it cost.

**How to apply:** three states, and a consumer has to tell all three apart.

| state | `drain.state` | `blocked` | `failure` |
|---|---|---|---|
| progressing | not `DRAIN_FAILED` | null | null |
| blocked, healthy | `DRAIN_FAILED` | set | **null** |
| failed | `DRAIN_FAILED` | null | set |

- `DrainStatus.blocked: DrainBlock?` (`reason`, `message`, `since`,
  `observations`) is the carrier. No `failureClass`: a block is always retried,
  so a field with one legal value would only invite the other.
- `ConditionType.DRAIN_BLOCKED` is derived from `blocked != null && failure ==
  null`, and `display.drainBlocked` renders it. It is the inverse of
  `NEEDS_ATTENTION` in what it tells somebody to do — *do not act* against *act*.
  **They are no longer disjoint** (superseded 2026-08-04, same day): the
  attention flag now also fires on the failure recorded on the *pass*, and a drain
  can be correctly waiting on players while the node it is on is unreachable. Both
  are then true and both are honest — they answer *is the drain stuck* and *must
  somebody act*. See [[escalation-ruling]]. Anything rendering the pair as one
  tri-state is now wrong; `API.md` §7 still says otherwise.
- `recordBlock` in `Failures.kt` carries `since`/`observations` forward exactly
  as `recordFailure` carries `occurredAt`/`attempts`.

## What deliberately did **not** move, so it is not re-opened

- **`DrainState.DRAIN_FAILED` still holds a blocked drain.** The state means
  *parked, not advancing* — the resume branch, the `RUNNING` phase mapping and
  `draining`/`drainInitiated` all key off it, and a new state would have meant a
  store migration and a `:api` badge decision for no behaviour change. The name
  is now slightly off and the record beside it says whether that is bad news.
  `display.drainState` therefore still shows `DRAIN_FAILED` on a healthy wait;
  the flag and `detail` carry the truth. Flagged to `drain-auditor` as the one
  place the new shape does not reach.
- **`DrainStatus` gets no `require` for blocked/failure disjointness.** Every
  check on that type is paid by the widest fleet read. A document with both is
  read, and the failure wins wherever they meet.
- **Requeue is unchanged.** A block returns `ReconcileOutcome.Retry`;
  `ReconcileLoop` keys off the outcome variant, never off `failureClass`.
- **`DRAIN_NO_DESTINATION` now means only the fleet-capacity case and *does*
  escalate.** Ruled by `drain-auditor` pre-implementation. `:core` cannot yet
  produce it (no proxy, so no destination search), so it is pinned at
  `escalated()` + `draftStatus` rather than through the loop.

See [[escalation-ruling]] for what the deletion replaced and
[[standalone-drain-decision]] for why there is no destination in the first place.
