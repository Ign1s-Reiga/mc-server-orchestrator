package mcorch.core.proxy

import mcorch.core.AssetMount
import mcorch.core.Labels
import mcorch.core.PortRequest
import mcorch.core.ResourceRequest
import mcorch.core.StorageRequest
import mcorch.core.WorkloadAsset
import mcorch.core.WorkloadSpec
import mcorch.schema.VelocityProxyDefinition
import mcorch.velocity.control.ControlProtocol
import java.security.MessageDigest

/**
 * Turns a validated [VelocityProxyDefinition] into the workload a node runs.
 *
 * Nothing here re-validates: the schema has already defaulted every field and
 * already refused a control port that collides with the player port.
 *
 * ## Three things this has to get right
 *
 * **`Labels.WORLD_DATA` is `false`, and it comes from the spec.** A drain reads
 * that label off the running container to decide whether it has to confirm a world
 * save before stopping, and an *absent* label means "this workload does not say",
 * which `WorkloadContract` reads as `true` on the safe side. A proxy has no world
 * and no `save-all` to confirm, so a proxy whose workload carried no label would
 * ask for a save nothing can answer, get `SaveOutcome.Unconfirmable`, classify it
 * permanent — and become a container **the orchestrator could never stop, ever**.
 *
 * The value is [mcorch.schema.ServerSpec.holdsWorldData] rather than a literal
 * `false` written here. That property is abstract on `ServerSpec` precisely so the
 * compiler asks every kind the question, and re-deriving the answer in the planner
 * would put it back to being a convention.
 *
 * **The plugin JAR is delivered, and the endpoint is told its token.** Velocity
 * loads plugins from a directory, so the JAR the orchestrator built has to be
 * inside the container — as an [AssetMount], which is a request a node has to
 * satisfy or refuse. Without it the proxy comes up perfectly well and has no
 * control endpoint, which is the failure mode where every backend behind it
 * becomes undrainable. Both halves of that sentence were false for a while: the
 * JAR was requested as storage and dropped by the node, and the token the plugin
 * authenticates with was never put in the container at all, so a published
 * endpoint served anyone who reached the port while `:core` politely sent a
 * bearer token it ignored.
 *
 * **The forwarding secret and the control token are coordinates.**
 * [WorkloadSpec.secretEnv] carries the references; the node resolves them at the
 * moment it hands them to the runtime. There is no field in this file that could
 * hold either material and no log line that could print one (CLAUDE.md invariant
 * 4).
 */
internal object VelocityWorkloadPlanner {
    const val PLAYER_PORT_NAME: String = "player"
    const val CONTROL_PORT_NAME: String = "control"

    /**
     * The image's plugin **staging** directory, not Velocity's plugin directory.
     *
     * Verified against `itzg/mc-proxy` (`scripts/run-bungeecord.sh`, image
     * `2026.7.1-java25`): the entrypoint does `cp -ru /plugins $BUNGEE_HOME`
     * before it starts the proxy, and `BUNGEE_HOME` is `/server`. So a JAR
     * mounted at `/plugins` arrives in `/server/plugins`, which is where
     * Velocity — whose working directory is `/server` — scans.
     *
     * **`/server/plugins` is the wrong answer and this constant used to hold
     * it.** Mounting into `/server` is not merely redundant: the same entrypoint
     * runs `chown -R bungeecord:bungeecord $BUNGEE_HOME` under `set -e`, and
     * chowning a read-only bind mount fails, so the container would exit during
     * startup. The staging directory is outside `BUNGEE_HOME` and is only ever
     * read.
     */
    const val PLUGIN_DIRECTORY: String = "/plugins"

    /**
     * The environment contract of the proxy image, verified against
     * `itzg/mc-proxy` `2026.7.1-java25`'s entrypoint.
     *
     * ## What is *not* here, and why
     *
     * `VELOCITY_PORT` and `VELOCITY_MAX_PLAYERS` were, and neither exists. The
     * image's entrypoint reads no such variable and Velocity itself takes both
     * from `velocity.toml` (`bind`, `show-max-players`), which the image
     * downloads a stock copy of. They looked like configuration in every test
     * that asserted on them and configured nothing at all.
     *
     * The consequence is worth stating plainly, because it is a live limitation
     * rather than a tidy-up: **this build cannot move the port Velocity listens
     * on.** The image installs a stock `velocity.toml` before the proxy starts
     * and that file decides the bind. `spec.network.port` still decides the port
     * mapping and the readiness ping, so it is a *claim about the image* — a
     * definition that names anything else is a proxy that never becomes ready,
     * visibly as `READINESS_TIMEOUT` rather than silently.
     * `VelocityProxyDefaults.PLAYER_PORT` is that claim and carries the evidence
     * for it.
     *
     * Making the port genuinely configurable means owning `velocity.toml`: the
     * image syncs `/config` into `/server` with environment interpolation, and it
     * does so *before* it installs its defaults with `--skip-existing`, so a file
     * placed there wins. That is the shape of the fix and it is a larger change
     * than this one, not something to smuggle in here.
     */
    const val TYPE: String = "TYPE"

    /**
     * Selects the proxy the image runs. **Without it the image runs
     * BungeeCord**, which is its documented default — a proxy that starts
     * perfectly, ignores modern forwarding, and cannot load a Velocity plugin,
     * so the control endpoint never exists and every backend behind it is
     * undrainable.
     */
    const val TYPE_VELOCITY: String = "VELOCITY"

    /**
     * The Velocity build the image downloads, pinned.
     *
     * ## An unpinned proxy is the critical on a timer
     *
     * The image resolves this to `latest` when it is unset, so the Velocity inside
     * a proxy container is decided at *container start* by whatever upstream had
     * published that morning — while the plugin mounted into it was compiled
     * against one API, and Velocity 4 is already an API break from 3.x. The day
     * upstream cuts the next one, a restarted proxy comes up `RUNNING`,
     * `ready = true` and serving players, with a plugin that failed to load and no
     * control endpoint. Nothing in the definition moved, so nothing in the spec
     * hash moved, so the loop could not tell that the workload it is looking at is
     * no longer the workload it asked for.
     *
     * Two properties follow from pinning it *and* putting it in [specHash], and
     * both are needed:
     *
     * - the version cannot change underneath a running proxy, so the state is not
     *   entered by an upstream release;
     * - changing this constant is a spec-hash change, so every proxy is drained and
     *   recreated onto the new build by the ordinary replacement path. "The plugin
     *   cannot load against this Velocity" becomes a state the loop can drift *out
     *   of* rather than only into.
     *
     * ## It tracks the plugin's compile target, and a test says so
     *
     * The value is `velocity` in `gradle/libs.versions.toml` — the `velocity-api`
     * `:velocity-plugin` compiles against. A JAR compiled against 4.0.0 is loaded
     * by whichever Velocity the operator's image runs, so these two are one
     * decision written in two places, and a comment asking the next reader to keep
     * them in step is not an enforcement point. `VelocityWorkloadPlannerTest` reads
     * the catalog's value out of a system property the build supplies and fails if
     * they differ, so a bump to one that forgets the other does not compile a green
     * suite.
     *
     * ## A hash input no operator can edit is a replacement with no exit
     *
     * The twenty-fifth audit's first warning, and it is a property of the *shape*
     * rather than of this value. A proxy whose spec hash moves is drained: its own
     * drain seals the login path and waits for the last player to log off, because
     * a fleet has one front door and there is nowhere to send anybody. That wait is
     * unbounded by design. So for every hash input the operator can edit — the
     * image, the memory, `maxPlayers` — the seal has an exit, which is to put the
     * value back; and for an input that lives in this file it had none. Bumping
     * this constant sealed every proxy in the fleet on its first pass after the
     * deploy, existing players kept playing, nobody could join, and the only ways
     * out were editing orchestrator source or `crictl stop`.
     *
     * That is why the value reaching [plan] and [canonicalSpec] is a *parameter*
     * with this constant as its default, supplied from
     * `ReconcilerConfig.velocityBuild` and from `MCORCH_VELOCITY_BUILD` at the top
     * of `:app`. The pin keeps every property it was added for — a running proxy's
     * Velocity cannot change underneath it, and a bump still drains the fleet onto
     * the new build by the ordinary replacement path — and gains the one it was
     * missing: an operator can hold their fleet on the build its containers were
     * created with, or lead a bump, without editing this file. Setting it to a
     * build the mounted plugin cannot load is a proxy with no control endpoint,
     * reported as `PROXY_CONTROL_UNREACHABLE`; that is a visible, revertable
     * mistake, which is the trade being made against a fleet-wide login blackout.
     *
     * **`plugin.protocol` is the same shape and deliberately has no such lever.**
     * It names what the mounted JAR *speaks*, so an operator pinning it would be
     * asserting something about an artefact rather than choosing a version, and the
     * honest repair for a protocol bump is the recreate. The exposure it leaves is
     * real and is named in the report rather than papered over here.
     */
    const val VELOCITY_VERSION: String = "VELOCITY_VERSION"

    /**
     * The default [VELOCITY_VERSION], pinned to the `velocity-api`
     * `:velocity-plugin` compiles against.
     *
     * The default rather than the value: see [VELOCITY_VERSION] for why a
     * deployment can pin its own, and [pinnedBuild] for the single place the two
     * become one answer.
     */
    const val VELOCITY_BUILD: String = "4.0.0"

    const val FORWARDING_SECRET: String = "VELOCITY_FORWARDING_SECRET"
    const val FORWARDING_MODE: String = "VELOCITY_FORWARDING_MODE"
    const val CONTROL_PORT: String = "MCORCH_CONTROL_PORT"

    /**
     * The control endpoint's bearer token, read by the plugin from the
     * environment ([mcorch.velocity.control.ControlConfig.ENV_TOKEN]).
     *
     * **Secret material, so it travels in [WorkloadSpec.secretEnv]** and never
     * in `env` — the same rule the forwarding secret follows (CLAUDE.md
     * invariant 4). The plugin treats an absent token as "no authentication
     * required", which is legitimate only for an endpoint that is not published;
     * the schema is what pairs `control.hostPort` with `control.tokenSecret`, and
     * this is the wire that made that pairing mean anything. Before it, `:core`
     * sent a bearer token to an endpoint that had been told nothing, so the
     * endpoint served whoever reached the port.
     */
    const val CONTROL_TOKEN: String = "MCORCH_CONTROL_TOKEN"

    const val INIT_MEMORY: String = "INIT_MEMORY"
    const val MAX_MEMORY: String = "MAX_MEMORY"

    /**
     * The Velocity build this deployment pins, resolved.
     *
     * **The one place the default is applied**, so the environment variable the
     * container is given and the `velocity.build` entry the hash is taken over
     * cannot come from different answers. They must not: a container created with
     * one and recorded under the other is a workload whose hash never matches, so
     * the loop drains and recreates it on every pass for ever.
     */
    private fun pinnedBuild(build: String?): String = build ?: VELOCITY_BUILD

    /**
     * @param build the Velocity build this deployment pins, or null for the one
     *   this orchestrator ships against ([VELOCITY_BUILD]).
     */
    fun plan(
        definition: VelocityProxyDefinition,
        build: String? = null,
    ): WorkloadSpec {
        val spec = definition.spec
        val name = definition.metadata.name
        val velocity = pinnedBuild(build)
        return WorkloadSpec(
            server = name,
            kind = definition.kind,
            image = spec.image,
            specHash = specHash(definition, velocity),
            // A proxy holds no world. This is the branch CLAUDE.md invariant 2
            // exempts by name — "only disposable lobbies and minigame instances
            // may be treated as ephemeral" — and a proxy qualifies for the
            // stronger reason that there is no `storage` block in its schema to
            // ask.
            //
            // The plugin JAR is *not* storage and never was. It used to be
            // spelled as an ephemeral mount at the plugin directory, which the
            // node discarded, so the class note below was describing something
            // that did not happen.
            storage = StorageRequest.Ephemeral,
            // The control channel, in the only form that reaches the container.
            assets = listOf(AssetMount(WorkloadAsset.VELOCITY_CONTROL_PLUGIN, PLUGIN_DIRECTORY)),
            resources =
                ResourceRequest(
                    memoryBytes = spec.resources.memory.bytes,
                    cpuMillicores = spec.resources.cpu?.millicores,
                ),
            hostname = name.value,
            env =
                buildMap {
                    // First, because without it none of the rest is a Velocity
                    // proxy at all. See [TYPE_VELOCITY].
                    put(TYPE, TYPE_VELOCITY)
                    // Second, and for a related reason: `TYPE` decides that this is
                    // Velocity at all, and this decides *which* Velocity. Unset, the
                    // image takes the newest published build at container start.
                    //
                    // The same resolved value the hash was taken over, by
                    // construction — see [pinnedBuild].
                    put(VELOCITY_VERSION, velocity)
                    put(FORWARDING_MODE, spec.forwarding.mode.wireValue)
                    put(CONTROL_PORT, spec.control.port.toString())
                    put(INIT_MEMORY, jvmMemory(spec.resources.heap.min.bytes))
                    put(MAX_MEMORY, jvmMemory(spec.resources.heap.max.bytes))
                },
            // Coordinates, never material.
            secretEnv =
                buildMap {
                    put(FORWARDING_SECRET, spec.forwarding.secret)
                    // Absent when the operator declared no token, which the
                    // schema allows only for an endpoint that is not published.
                    // The plugin reads absence as "no authentication", so the
                    // two have to agree — and they do, because both are this one
                    // field: `ControlChannel` sends the token from
                    // `spec.control.tokenSecret` and this is the same reference.
                    spec.control.tokenSecret?.let { put(CONTROL_TOKEN, it) }
                },
            ports =
                buildList {
                    add(
                        PortRequest(
                            name = PLAYER_PORT_NAME,
                            containerPort = spec.network.port,
                            hostPort = spec.network.hostPort,
                        ),
                    )
                    add(
                        PortRequest(
                            name = CONTROL_PORT_NAME,
                            containerPort = spec.control.port,
                            // Published only when the operator asked for it, and
                            // the schema has already made `tokenSecret` required
                            // in that case. Unpublished is the default because
                            // this port can move every player in the fleet.
                            hostPort = spec.control.hostPort,
                        ),
                    )
                },
            labels =
                Labels.forServer(name, definition.kind) +
                    mapOf(
                        // The load-bearing one. See the class note.
                        Labels.WORLD_DATA to Labels.booleanLabel(spec.holdsWorldData),
                        // Velocity has no RCON and nothing else that replies, so
                        // there is no channel through which a save could ever be
                        // confirmed. Recorded honestly: with `WORLD_DATA` false
                        // the drain never asks, and if it ever did it would get
                        // the truth rather than a guess.
                        Labels.SAVE_CONFIRMABLE to Labels.booleanLabel(false),
                        // What protocol the container was built to speak, on the
                        // container, for the same reason the two above are. A
                        // proxy running an older image is running an older plugin,
                        // whatever the definition says today.
                        CONTROL_PROTOCOL to ControlProtocol.VERSION,
                    ),
        )
    }

    /** The control protocol the mounted plugin JAR speaks, recorded on the workload. */
    const val CONTROL_PROTOCOL: String = "mcorch.dev/control-protocol"

    /**
     * A fingerprint of everything that cannot change without recreating the
     * container.
     *
     * What is absent matters as much as what is present. The proxy-side drain
     * timeouts are not here — raising one must not restart a proxy with players on
     * it — and neither is `backends.fallback`, which is read fresh every pass. The
     * *selector* is not here either: enrolling a new backend must not recreate the
     * proxy, and it does not have to, because registration is a level-triggered
     * call rather than a startup argument.
     *
     * The forwarding secret contributes its **coordinates**, never its material,
     * so rotating the value behind the reference does not appear here and the
     * running proxy keeps the secret it was created with until something else
     * recreates it. That is the same rule the Paper planner follows for RCON, and
     * it is what stops a rotation restarting the whole fleet at once.
     *
     * Two entries are not read off the definition at all — the Velocity build and
     * the control protocol — and they are the two that decide whether the mounted
     * plugin can *load and be spoken to*. A hash that named only operator-supplied
     * fields could not tell a running proxy from one built against a different
     * Velocity, which is precisely the state that made a proxy undrainable. See
     * [VELOCITY_VERSION].
     */
    fun specHash(
        definition: VelocityProxyDefinition,
        build: String? = null,
    ): String {
        val canonical = canonicalSpec(definition, build)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    /**
     * What [specHash] digests, before it is digested.
     *
     * Separate so a test can assert *membership* rather than only that two hashes
     * differ. Most entries here are checked by varying the definition, which a
     * constant like the control protocol has no way to express — and "the hash is
     * equal to itself" is an assertion that cannot fail.
     *
     * The `velocity.build` entry keeps its wording and its position when [build] is
     * unset, and that is load-bearing rather than incidental: a proxy created by a
     * build that had no lever here must not be replaced by one that has, or the
     * lever would be introduced by way of the outage it exists to prevent.
     */
    fun canonicalSpec(
        definition: VelocityProxyDefinition,
        build: String? = null,
    ): String {
        val spec = definition.spec
        return buildList {
            add("kind=${definition.kind.wireValue}")
            add("image=${spec.image.canonical}")
            add("velocity.build=${pinnedBuild(build)}")
            add("memory=${spec.resources.memory.bytes}")
            add("cpu=${spec.resources.cpu?.millicores ?: "unset"}")
            add("heap.max=${spec.resources.heap.max.bytes}")
            add("heap.min=${spec.resources.heap.min.bytes}")
            add("network.port=${spec.network.port}")
            add("network.hostPort=${spec.network.hostPort ?: "none"}")
            add("control.port=${spec.control.port}")
            add("control.hostPort=${spec.control.hostPort ?: "none"}")
            add("control.token=${spec.control.tokenSecret?.let { "${it.name}/${it.key}" } ?: "none"}")
            add("forwarding.mode=${spec.forwarding.mode.wireValue}")
            add("forwarding.secret=${spec.forwarding.secret.name}/${spec.forwarding.secret.key}")
            add("maxPlayers=${spec.maxPlayers}")
            add("plugin.protocol=${ControlProtocol.VERSION}")
        }.joinToString("\n")
    }

    private const val HASH_BYTES = 16

    private const val GIB = 1024L * 1024L * 1024L
    private const val MIB = 1024L * 1024L
    private const val KIB = 1024L

    /** `6G`, not `6Gi`. The numbers are identical — the JVM's suffixes are already binary — but `-Xmx6Gi` does not parse. */
    fun jvmMemory(bytes: Long): String =
        when {
            bytes % GIB == 0L -> "${bytes / GIB}G"
            bytes % MIB == 0L -> "${bytes / MIB}M"
            bytes % KIB == 0L -> "${bytes / KIB}K"
            else -> bytes.toString()
        }
}
