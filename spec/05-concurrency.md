# 05 — Concurrency

## The bottleneck is not the relay

From `docs/failure-modes.md`:

> **RCON** (`rcon-cli`) dispatches onto the main thread and blocks until it
> replies.

Adding workers to the relay does not move this, and a low user count does not
help. The constraint is not how many people are typing — it is that **one server
has one main thread, and the console competes with the game for it.**

## The three rules

### 1. Parallel across servers

N servers are N independent main threads. A per-node relay is the right shape to
exploit that, and this is where all the real throughput is. It is also the
dimension that scales with fleet size — which is the dimension that matters once
Forge support brings larger fleets.

### 2. Strictly serial within a server

Concurrent commands to one server queue on its tick loop and consume tick budget
while they wait. Accepting them concurrently does not make them concurrent; it
only hides the queue from the caller and makes the latency unattributable.

Serialise per server, and let the queue be visible — see
[07-api.md](07-api.md) for how a full queue is reported.

### 3. Subordinate to an in-flight drain

Console commands are refused or queued behind a running save. **Never raced
against it.**

This is a data-safety rule, not a politeness one. From the same file:

> a save request that is delivered but unconfirmed is never re-sent, so a server
> deleted during ordinary world generation — when RCON is up but the main thread
> is busy for a minute or more — becomes permanently undeletable.

Console traffic is a way to make the main thread busy for a minute or more. An
unthrottled console can manufacture the undeletable-server condition that
`docs/operating.md` documents as its first surprising behaviour — the one an
operator is least equipped to diagnose, because from the outside the server looks
healthy.

The chain is worth stating in full, because it is not obvious:

```
operator runs console commands
  → commands queue on the main thread
    → the drain's save-all waits behind them
      → the save exceeds its timeout
        → the drain aborts unconfirmed, container left running
          → the server cannot be deleted
```

Every step of that is existing, documented behaviour. The console only supplies
the first one.

## Forge sharpens all of it

- A modded server's 50 ms tick budget is already tight, so console commands take
  a proportionally larger bite.
- Mod-registered commands can be genuinely expensive — chunk and entity queries
  in particular.
- Modded world saves are long, which widens the window in which console
  contention can push a save past its timeout.

The interaction between rule 3 and modded save durations is the one to watch
during implementation: the more headroom `spec.lifecycle` gives a save, the
longer the console is subordinate to it, and the more visible the queueing
becomes to an operator who does not know why.

## What the relay does *not* fix

Worth recording so nobody re-derives it: moving from per-command `ExecSync` to a
relay with a persistent connection removes the per-command TCP connect and RCON
auth handshake. It does **not** remove main-thread dispatch — RCON lands there
regardless of who connects or how long the connection has been open.

At human typing speed the per-command handshake was never the real cost. The
relay's justification is session semantics and being a single place to enforce
the three rules above, not latency.
