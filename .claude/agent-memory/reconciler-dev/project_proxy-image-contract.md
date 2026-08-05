---
name: proxy-image-contract
description: What :core assumes about the inside of a Velocity proxy container, verified against itzg/mc-proxy's own entrypoint and a real run — including the three things that are decided by the image rather than by us
metadata:
  type: project
---

Everything `:core` assumes about the *inside* of a proxy container is written
against `itzg/mc-proxy` and lives in `VelocityWorkloadPlanner`, so a correction
stays a one-file change — the same arrangement `PaperImageContract` has (see
[[unverified-paper-image-contract]]).

**Verified 2026-08-06** against `itzg/mc-proxy:2026.7.1-java25`, containerd
2.3.3, by `app/src/integrationTest` bringing a proxy up through the reconcile
loop: plugin loaded, control endpoint answering, 401 without the token and 200
with it, ready in about 20 seconds end to end.

Facts worth keeping, none derivable from our code:

- **`TYPE` defaults to `BUNGEECORD`.** A proxy created without `TYPE=VELOCITY` is
  not a Velocity proxy at all: modern forwarding does not apply and no Velocity
  plugin can load, so nothing else about the control channel matters.
- **`/plugins` is a staging directory, `/server/plugins` is Velocity's.** The
  entrypoint does `cp -ru /plugins $BUNGEE_HOME` (`BUNGEE_HOME=/server`) before
  starting. Mount the JAR at `/plugins`; mounting into `/server` also trips the
  `chown -R bungeecord:bungeecord $BUNGEE_HOME` the script runs under `set -e`,
  because chowning a read-only bind mount fails and the container exits.
- **There is no port variable, and no player-count variable.** `VELOCITY_PORT`
  and `VELOCITY_MAX_PLAYERS` do not exist anywhere — not in the image, not in
  Velocity. Both are `velocity.toml` (`bind`, `show-max-players`).
- **The image installs a stock `velocity.toml` before Velocity can generate
  one**, fetched at container start from a third-party defaults repository
  (`DOWNLOAD_DEFAULTS` → `Shonz1/minecraft-default-configs`). It binds
  `0.0.0.0:25577`. So the port a proxy listens on is decided by a file this
  project does not control, and `VelocityProxyDefaults.PLAYER_PORT` is a *claim
  about the image* rather than a request.
- **The route to owning that config exists and is not taken yet.**
  `processConfigs` syncs `/config` into `/server` with environment interpolation
  (`${CFG_*}`) and only *then* installs defaults with `--skip-existing` — so a
  `velocity.toml` mounted at `/config` wins. That is how `spec.network.port` and
  `spec.maxPlayers` would become real requests. It means owning a config file
  whose `config-version` tracks the Velocity the image downloads.
- `INIT_MEMORY` / `MAX_MEMORY` are real and become `-Xms` / `-Xmx`.
- The image ships `mc-monitor`, so the readiness ping works the same way it does
  for Paper.
- A proxy is `RUNNING` and answering its control endpoint in roughly 15 seconds
  once the image is local — nothing like Paper's 60–95 second window, because
  there is no world to generate.

## The one that cost a run

`VELOCITY_FORWARDING_SECRET` is still **unverified for Velocity 4.0.0**. The
image generates `/server/forwarding.secret` when the file is absent, so a proxy
starts either way, and the integration test does not yet prove a backend
authenticates against the proxy. Do not read a green proxy run as evidence that
modern forwarding is wired.

**How to apply:** when anything about the proxy container is in question, read
`scripts/run-bungeecord.sh` in `itzg/docker-mc-proxy` — it is one file and it is
the whole contract. Re-verify the player port whenever the image tag moves; the
integration suite is what does that.
