---
name: drain-protocol
description: The procedure for safely stopping a Minecraft server container. Use this skill whenever implementing container stop or removal, restarts, rescheduling, the stop grace period, player transfers, or world saving — even if nobody uses the word "drain". Applying an ordinary container stop to a Minecraft server loses player progress.
---

# Drain protocol

A Minecraft server is not a container you can stop at any moment. Every path that stops or removes one goes through this procedure.

## Why an ordinary container stop is not enough

A plain CRI stop sends a termination signal and then kills after the grace period. For a Minecraft server:

- The last several minutes of play are still in memory, unsaved
- Saving a world can take tens of seconds
- Connected players are disconnected unless transferred first
- A kill mid-save can corrupt region files

## The correct procedure

Do not reorder these. Confirm each step completed before the next.

### 1. Mark the drain as started

Record on the server's desired/observed state that a drain is in progress. From here the reconcile loop stops treating this server as a placement target and does not "heal" it back to running.

### 2. Stop new joins (Velocity side)

Instruct the proxy to remove this backend from routing for new players. **Do not deregister it yet** — deregistering disconnects the players still on it. Only "send nobody new".

### 3. Secure a destination

Choose where players go and confirm capacity. If none can be secured, **abort the drain and record the failure**. Kicking players to make progress is not an option.

### 4. Transfer the players

The proxy moves connected players to the destination.

- Individual transfers can fail; retry them, and abort the drain at the retry limit.
- Notify players before transferring (Adventure Component / MiniMessage).
- Confirm either everyone transferred or a zero-players report.

### 5. Save the world

Request a save and **wait for the completion notification**. Do not proceed at the point the request was sent. On timeout, abort and leave the container running.

### 6. Deregister the backend (Velocity side)

Only after confirming zero players and a completed save, unregister the backend from the proxy.

### 7. Stop the container

Only now issue the CRI stop. The stop grace period must already exceed the maximum save duration. The grace-period kill is the **last-resort safety net** for a container disappearing without this protocol — it is not the normal save path. Persistent world mounts survive the container's removal.

## On abort

If any of steps 3–5 fails:

- Do not stop the container. Players stay where they are.
- Record the reason on observed status.
- Requeue with exponential backoff; at the limit, wait for human intervention.
- **Reaching the retry limit is never a reason to force-stop.**

## Notes per path

| Path | Note |
|---|---|
| Definition change that requires recreation | Drain the old container before creating the replacement. Never recreate under live players |
| Restart of an unhealthy server | Still drains first unless the server is already unreachable (no players to lose) |
| Reschedule to another node (future) | Drain on the source node before starting on the destination |
| Pool scale-down | The loop drains the chosen instance before removing it |

## Detailed references

Both live in the repository's own documentation rather than under this skill,
because source across `:schema`, `:core` and `:velocity-plugin` cites
`failure-modes.md` by item number and a contributor reading those comments has to
be able to find it.

- State transitions and per-state timeouts: `docs/state-machine.md`
- Failure patterns, remedies, and forbidden implementations: `docs/failure-modes.md`

Read both before implementing. "Not enough notice" in particular is covered in failure-modes.
