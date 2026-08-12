# Troubleshooting

A symptom index. `docs/operating.md` explains behaviours that are deliberate and
surprising; this one starts from what you are looking at and works backwards.

Every entry here has actually happened during development.

---

## A client cannot connect: "Connection refused"

**Almost always `hostPort`.** Two fields look interchangeable and are not:

| Field | Where it applies |
|---|---|
| `spec.network.port` | the port the server listens on **inside its sandbox** |
| `spec.network.hostPort` | **unpublished by default** — what binds on the host |

A server with `network.port` set and `hostPort` unset is running correctly and
reachable from nowhere. Its sandbox has no port mappings and nothing is listening
on the host.

Confirm from outside the orchestrator:

```bash
crictl --runtime-endpoint unix:///run/mcorch-dev/containerd.sock inspectp <podid> | grep -i -A6 port_mappings
ss -ltn | grep <port>
```

Empty mappings and no listening socket is the whole diagnosis. The pod's own IP
(`10.87.x.y` on the dev CNI bridge) will accept a connection on `network.port`,
which is a useful way to prove the server itself is healthy.

**On a fleet behind a proxy, this is not the fix.** Backends are meant to stay
unpublished; the proxy is the front door and carries the `hostPort` players type.
Publishing a backend directly bypasses the thing that makes drains work.

Note that adding `hostPort` reshapes the container — see `api/API.md` §5.

---

## Creating a server does nothing / the button appears dead

Check whether the request is being **rejected** rather than lost. A `422` from
`POST /api/v1/servers` is schema validation, and the body names every problem
with a dotted field path and a line/column into the document you sent:

```json
{ "code": "VALIDATION_FAILED", "message": "the definition has 1 problem",
  "violations": [ { "field": "spec.eulaAccepted", "problem": "is required" } ] }
```

`POST /api/v1/validate` does the same thing and writes nothing, so it is the
cheapest way to test a document by hand.

If a client maps violations onto form fields by path, a violation whose path has
no rendered input will be dropped silently — the form looks clean and the button
looks broken. Rendering unmatched violations somewhere unconditional is worth the
space.

---

## Everything sits in `PENDING` with `NODE_UNAVAILABLE`

The orchestrator cannot reach a container runtime. It opens the CRI channel
lazily and issues no RPC at startup, so the process starts and serves the whole
API with no containerd present — which is a feature when developing a client and
a puzzle when you expected containers.

Check the endpoint it was given and whether anything answers:

```bash
tr '\0' '\n' < /proc/<pid>/environ | grep MCORCH_CRI_ENDPOINT
crictl --runtime-endpoint <endpoint> version
```

A socket file existing proves nothing; ask `crictl`. Note its default timeout is
short — on a slow filesystem a healthy daemon can time out on the first probe and
answer normally on the second. Retry before concluding the daemon is dead.

---

## A proxy is up but nobody can join

Read `BACKENDS_RESOLVED`'s message. Two failure shapes hide behind one symptom:

- **`backends: null`** — nothing has looked yet. Resolves itself.
- **`matched: 0`** — the selector matched nothing. The proxy is routing players
  nowhere and this needs a human: either a server needs the label, or the
  selector is wrong.

A message like `1 of 1 matched server(s) are in the routing table; 0 can receive
a transfer` is the second number to worry about. Registered is not the same as
able to take players, and drain step 3 needs the second — a fleet where nothing
can receive a transfer cannot drain anything.

Remember that `spec.backends.selector` matches `metadata.labels` on the
definition, so a backend with no labels joins no fleet.

---

## RCON is configured but not working

There is no `enabled` field to check — RCON is standard, and a definition
carrying `enabled` is rejected as an unknown field.

So the causes are the ones configuration cannot fix. RCON dispatches onto the
game's main thread, which means a server busy generating world, or wedged, will
not answer however correct the block is. The other common cause is a password the
server does not have: rotating the secret does not reach a running container,
because the value is handed to it at creation.

The password lives in the secret store and is referenced by coordinates:

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" \
  --data-binary 'the-password' \
  http://127.0.0.1:8080/api/v1/secrets/<name>/<key>
```

Raw body, not JSON. Secrets are never readable back — `GET` on a key is a
deliberate `405`. Writing an existing key **replaces** it silently, so check the
`replaced` field in the response if you did not mean to.

---

## An edit was accepted and nothing happened

If the server also reports `NEEDS_ATTENTION` with a `PERMANENT` failure, the loop
has stopped taking passes on it and your edit is sitting in desired state
unapplied. This is the stalled-drain state; see `docs/operating.md` note 1,
which also lists the two ways out.

The most common route in is an edit to `spec.network.rcon` on a persistent server
whose running container has stopped answering. That edit cannot take effect —
`rcon` applies to the next container, and the current one cannot be replaced
without a drain, which is what needs the channel that is not answering.

---

## `428` or `409` on a `PUT`

- `428 PRECONDITION_REQUIRED` — `PUT` requires `If-Match`. Send the `ETag` you
  read.
- `409 VERSION_MISMATCH` — the stored definition changed since that `ETag`.
  Re-read, re-apply your edit, retry.
- `409 TERMINATING` — the server is being deleted and will not accept edits.

Repairing a definition the store cannot read is the one case for `If-Match: *`.

---

## A server will not delete

Expected, in one specific case: a `PaperServer` with persistent storage and RCON
disabled cannot be drained, because the world save cannot be confirmed, so
`DELETE` never completes and the container keeps running. This is correct
behaviour, not a bug — see `docs/operating.md` note 1 for both exits.

There is no force flag anywhere in the API, and `RouteTableTest` asserts that no
route matching stop, kill or force exists.

---

## Starting over on a development host

The orchestrator's whole state is its data directory. With the process stopped:

```bash
crictl --runtime-endpoint <endpoint> pods -q | xargs -r -n1 crictl --runtime-endpoint <endpoint> rmp -f
rm -rf /tmp/mcorch-dev
```

Stop the orchestrator **first**, or the reconcile loop will recreate containers
while you remove them. Removing a sandbox can exceed `crictl`'s RPC deadline and
report a timeout while still having stopped it; re-running the removal is safe.

This discards every world, definition and secret. The store recreates itself on
the next start, and any artefacts the deployment mounts — today the Velocity
control plugin JAR under `MCORCH_ASSET_DIR` — have to be staged again.
