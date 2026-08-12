# 03 — Every existing endpoint acquires a question

**This is the bulk of the work, and it is not about the console.**

Today `api/API.md` can say *"any authenticated caller can do anything the API
offers"* — one sentence, true of every route, requiring no per-route thought. The
moment tiers exist, that sentence is replaced by **one decision per endpoint**,
and every one of them has to be made.

Getting these wrong is not an access-control inconvenience. `api/API.md` §12 says
why:

> every mutating endpoint can request a drain, and a drain is how a Minecraft
> server stops.

## 1. The two that are data-safety decisions

**`DELETE /api/v1/servers/{name}`** tombstones the definition, and the loop then
runs the drain and stops the container. It is the endpoint that ends a server.

**`PUT /api/v1/servers/{name}`** is not obviously worse than a `GET` until you
read what an edit does: a spec change makes the loop **drain the running server
and replace it**. `api/API.md` §5 has a whole section — *"Which edits replace the
container"* — on exactly this. A tier that may `PUT` may cause every player on a
server to be transferred and the container recreated.

**`PUT` is assigned to `Operator` anyway, and that is a decision rather than an
oversight.** An `Operator` who may create servers and edit manifests but may not
edit a *running* one is an `Operator` who cannot do the job the tier exists for.
The mitigation is that the replacement still goes through the drain — nobody is
disconnected, the world is saved first — so the cost of a careless `PUT` is a
restart, not lost data. That is a different class of mistake from `DELETE`, which
is why they land on different tiers.

What this does mean: **an `Operator` can cause a fleet-wide restart** by editing
several manifests, and nothing rate-limits that. Worth knowing before granting
the tier.

## 2. The assignment

| Route | Tier | Note |
|---|---|---|
| `GET /healthz` | none | Unauthenticated today and stays so |
| `GET /api/v1/meta` | `Member` | |
| `POST /api/v1/auth/session` | none | Establishes a credential; checks the credential itself |
| `GET` / `DELETE /api/v1/auth/session` | any | Your own session |
| `GET /api/v1/servers`, `/{name}`, `/{name}/status` | `Member` | See §3 — this is less obviously safe than it looks |
| `GET /api/v1/stream` | `Member` | Same content as the reads, incrementally |
| `POST /api/v1/validate` | `Member` | Parses a document, touches nothing |
| `POST /api/v1/servers` | `Operator` | Creates, and only creates. Never overwrites |
| `PUT /api/v1/servers/{name}` | `Operator` | Updates the manifest. **Decided knowingly** — see §1 |
| `DELETE /api/v1/servers/{name}` | **`Superuser`** | Ends a server — §1 |
| `GET /api/v1/secrets` | `Superuser` | Coordinates only, never material — but see §3 |
| `PUT` / `DELETE /api/v1/secrets/…` | **`Superuser`** | Writes the forwarding secret and the RCON password |
| `POST /api/v1/servers/{name}/console` | per `spec.console` | The console's own gates, on top of this |

`POST /servers` sitting below `PUT` and `DELETE` is deliberate and is the one
row most likely to look wrong. Creating a server cannot stop or replace an
existing one; the failure mode is a wasted container, which is recoverable. The
failure mode of `PUT` and `DELETE` is players disconnected and a world stopped.

## 3. Read-only is not the same as harmless

The reads are the rows most likely to be waved through, and two of them deserve
a second look.

**A server definition carries secret *coordinates*.** `spec.network.rcon.passwordSecret`,
`spec.forwarding.secret` and `spec.control.tokenSecret` are `{name, key}` pairs.
They are not material — `api/API.md` §13 is unambiguous that no endpoint resolves
one — but they are a **map of where material lives**, which is the useful half of
reconnaissance. Open decision 2 is whether `Member` should see them, or whether
definitions render with coordinates elided below `Superuser`.

**A proxy's status counts every player in the fleet.** `status.backends[].players`
is `{online, max, observedAt}` — no identities, by construction. That is fine to
expose at `Member`; it is noted here so that nobody later "improves" it into
something that names players and quietly widens what the lowest tier can see.

## 3.5 `DELETE` semantics — settled, and specified elsewhere

The tier is `Superuser`. What that `Superuser` may *do* is a separate question,
and it is now answered in [`../termination/`](../termination/README.md): a forced
path exists, as `DELETE ?force=true`, restricted to this tier.

It is specified there rather than here because it is a drain change and a schema
change, not an authorization one — and because it can lose world data, which
deserves a document that says so at the top rather than a subsection of a
permissions table.

Two things from it that belong in this table's reading:

- **The forced form requires `Superuser`**, and `RouteTableTest` gains an
  assertion of that tier rather than keeping its current blanket claim that no
  route can stop a container. That claim stops being true, and a test left green
  while the capability arrives is a test that no longer checks what it says.
- **It skips the drain's patience, never its save.** The distinction is the whole
  design and the reason the tier alone was never sufficient to settle this.

## 4. The default for a route that has not been decided

**Refuse.** A route with no tier assigned is `Superuser`-only until somebody assigns
one.

The alternative — an unassigned route falls through to "any authenticated
caller" — reproduces today's behaviour exactly, which means a route added later
is wide open and nothing fails to warn you. Failing closed makes an omission
visible as a `403` on the first call rather than invisible forever.

This should be structural: the route table carries a tier per entry, and a route
registered without one does not compile. `RouteTableTest` already asserts
properties of that table and is the natural place to assert this one.

## 5. What must not happen

**Do not ship the console's tier gate before this.** A tier honoured by exactly
one endpoint is worse than no tier at all: an operator who sees `Member` refuse a
console command reasonably concludes `Member` is constrained, and that conclusion
would be false everywhere else in the API.

The console is the reason this is being written. It is not the thing that
consumes it first.
