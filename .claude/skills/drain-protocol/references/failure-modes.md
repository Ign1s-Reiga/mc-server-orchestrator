# Failure patterns and remedies

## Forbidden implementations

Each looks reasonable and loses player data. Flag any as critical in review.

### 1. Force-stop after a timeout

```
if (elapsed > timeout) {
    cri.stopContainer(id, timeout = 0)   // forbidden
}
```

A timeout tells you the save has not finished. It does not tell you it is now fine to stop. Transition to `DrainFailed` and wait for a human.

### 2. Conflating "save requested" with "save completed"

Sending the save request is not the save finishing. Wait for the completion notification, and make sure the server-side agent only sends it after the save actually completes.

### 3. Deregistering first

Removing the backend from the proxy before transferring disconnects everyone still connected. Order: stop new joins, transfer, then deregister.

### 4. Kicking on transfer failure

Kicking players to reach zero when the destination is full or still starting looks like nobody was lost, but their latest activity can go unsaved before the disconnect.

### 5. Recreating under live players

On a definition or image change, creating the replacement container while the old one still has players. Drain the old one first, then create the replacement.

### 6. Treating the grace-period kill as the save path

Relying on the stop grace period to "give it time to save" offers no guarantee of completion and swallows failures. It is a safety net only.

### 7. Changing behaviour at the retry limit

"Give up and stop after three attempts" is the same as item 1. At the limit, you stop trying — you do not stop the container.

## When there is not enough notice

Host shutdown or a forced reschedule can leave too little time for the full procedure. Degrade in this order. **Skipping the save is never an option.**

1. Shorten the transfer wait: skip destination search and go straight to a pre-designated fallback.
2. Reduce per-player transfer retries.
3. If it still will not fit, abandon the transfer but **complete the save**. Players disconnect, but progress survives.

Sacrifice order: connection continuity first, world-data integrity last. Never invert it.

Structurally, keep servers with persistent worlds off hosts prone to abrupt termination, and reserve those for explicitly-ephemeral kinds. Decide this at scheduling time, once the scheduler is real.

## Partial failures

| Situation | Handling |
|---|---|
| Only some players fail to transfer | Retry just those; do not roll back the successful ones |
| The server-side agent stops responding | Check the server is alive via Server List Ping. If it is, save completion cannot be confirmed → `DrainFailed` |
| Agent responds but the server does not | Server may be frozen. Attempt the save and wait the full grace period |
| The proxy goes down | Destination lost; abort the drain and wait for proxy recovery |
| Container stop fails after a completed save | Save is done, so retrying the stop is safe. This is the one place a force stop is acceptable |

## Scenarios to verify

Cover at least these with `integration-tester`:

- Draining a server with zero players (happy path)
- The drain aborts and the container survives when the destination is full
- The container survives a save timeout
- New players are never routed to a draining server
- A definition change drains the old container before creating the replacement
- Persistent world data survives container removal
