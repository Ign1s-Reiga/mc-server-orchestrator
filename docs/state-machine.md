# Drain state machine

The states a drain moves through, what advances each, and the timeout each is
given. `:core` implements this and `:schema` names the states a client sees — see
[`server-lifecycle.md`](server-lifecycle.md) for the wire names and what a
dashboard should render for each.

The timeouts below are the prescribed defaults. Where the code deliberately
allows something different it says so and cites this file.

## States

| State | Meaning | Advances when | Suggested timeout |
|---|---|---|---|
| `Ready` | Running normally | A drain is requested | — |
| `DrainRequested` | Drain marked | State records it | 5s |
| `Sealed` | New joins stopped; existing players still connected | Proxy acknowledges | 10s |
| `TargetResolved` | Destination secured | Destination has capacity | 30s |
| `Transferring` | Moving players | Zero-players report | 120s (extend by player count) |
| `Saving` | World save in progress | Save completion notification | 180s |
| `Deregistered` | Backend removed from proxy | Deregistration acknowledged | 10s |
| `Stopping` | CRI stop issued | Container gone | stop grace period |
| `DrainFailed` | Aborted | Human intervention, or retry after backoff | — |

## Transitions

```
Ready
  └─ drain requested ─→ DrainRequested
                          └─→ Sealed
                                └─→ TargetResolved ──(no destination)──→ DrainFailed
                                      └─→ Transferring ──(retry limit)──→ DrainFailed
                                            └─→ Saving ──(save timeout)──→ DrainFailed
                                                  └─→ Deregistered
                                                        └─→ Stopping
```

There is no edge from `DrainFailed` to `Stopping`. If it failed, it does not get stopped.

## Choosing timeouts

- Set the `Saving` timeout to roughly three times the measured save duration for that world size.
- The container stop grace period must exceed the `Saving` timeout. If inverted, the runtime kills the container partway through the correct procedure.
- Scale the `Transferring` timeout with player count. A fixed value always fails on a full server.

## Reflecting state

The reconcile loop records the drain state as part of observed status each pass. Carry at least:

- `Draining` — whether a drain is in progress
- `PlayersEvacuated` — whether zero players has been confirmed
- `WorldSaved` — whether save completion has been confirmed
- `Ready` — whether the server can take traffic

If observed status stops advancing, the loop has died — treat a stalled drain state as an alert, not a steady state.

## Idempotency

The loop may re-enter any state any number of times. Read the current drain state from stored status and do not re-send side effects already issued (transfer requests, save requests). A duplicate save request in particular puts needless load on the server. Creating a container that already exists, or stopping one already stopped, must be treated as success, not retried blindly.
