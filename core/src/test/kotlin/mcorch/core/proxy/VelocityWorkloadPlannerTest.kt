package mcorch.core.proxy

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mcorch.core.StorageRequest
import mcorch.core.WorkloadAsset
import mcorch.core.proxyDefinition
import mcorch.schema.SecretRef
import mcorch.schema.VelocityProxyDefaults
import mcorch.store.getOrThrow
import org.junit.jupiter.api.Test

/**
 * What the proxy planner puts in the container, and what it deliberately does
 * not.
 *
 * Every assertion here is about something *absent* from the workload that a
 * running proxy needs, which is the class of defect that survived twenty-three
 * static audits and only fell to an integration run: the plugin JAR that was
 * requested as storage and dropped, the control token that was never written at
 * all, and the `TYPE` variable without which the image runs a different proxy
 * entirely.
 */
internal class VelocityWorkloadPlannerTest {
    private fun token(): SecretRef = SecretRef.of("front-01-control", "token").getOrThrow()

    /**
     * The control channel drain steps 2, 4 and 6 run through, requested in the
     * one form a node has to honour or refuse.
     *
     * It was `StorageRequest.Ephemeral(mountPath = PLUGIN_DIRECTORY)`, and the
     * single-host node discarded the path — so this asserts the *kind* of request
     * as much as its contents. Storage is where a world goes; an artefact this
     * orchestrator ships is not storage and cannot be smuggled through a field
     * whose only implementation ignores it.
     */
    @Test
    fun `the control plugin is requested as an asset, and the proxy holds no storage`() {
        val spec = VelocityWorkloadPlanner.plan(proxyDefinition())

        val asset = spec.assets.single()
        asset.asset shouldBe WorkloadAsset.VELOCITY_CONTROL_PLUGIN
        asset.directory shouldBe VelocityWorkloadPlanner.PLUGIN_DIRECTORY
        // Velocity discovers plugins by scanning for `*.jar`; a file that lands
        // under any other name is a plugin that silently does not load.
        asset.destination shouldBe "/plugins/mcorch-velocity-control.jar"

        spec.storage shouldBe StorageRequest.Ephemeral
    }

    /**
     * The Velocity the container downloads is pinned, and pinned to the API the
     * mounted plugin was compiled against.
     *
     * Unset, the image takes whatever upstream published most recently, at
     * *container start*. A restart after a breaking upstream release then produces
     * a proxy that is `RUNNING`, `ready` and serving players with a plugin that
     * failed to load — and because no field of the definition moved, no spec-hash
     * input moved either, so the loop cannot notice or repair it. That is the
     * twenty-fourth audit's critical arriving on a timer rather than by an
     * operator's mistake.
     *
     * ## The property this asserts is not "the string is 4.0.0"
     *
     * It is that the constant equals the `velocity` version in
     * `gradle/libs.versions.toml`, which is the `velocity-api` `:velocity-plugin`
     * compiles against. The value comes from the build (`core/build.gradle.kts`
     * sets the system property) rather than being written here, so this test cannot
     * be satisfied by editing it to match a stale constant. An absent property is a
     * failure and not a skip: a test that quietly stops checking is the shape of
     * gap this exists to close.
     */
    @Test
    fun `the Velocity build is pinned, hash-bearing, and matches the plugin's compile target`() {
        val fromCatalog =
            checkNotNull(System.getProperty("mcorch.velocityApiVersion")) {
                "the build must supply `mcorch.velocityApiVersion` from libs.versions.toml; without it this " +
                    "assertion cannot run and the two pins can drift apart unnoticed"
            }
        VelocityWorkloadPlanner.VELOCITY_BUILD shouldBe fromCatalog

        val spec = VelocityWorkloadPlanner.plan(proxyDefinition())
        spec.env[VelocityWorkloadPlanner.VELOCITY_VERSION] shouldBe fromCatalog

        // In the hash, so bumping the pin recreates every proxy through the ordinary
        // replacement drain instead of leaving running containers on a Velocity the
        // plugin cannot load against. Asserted on the hash's *input*: there is no
        // definition to vary that would move a constant, and comparing a hash with
        // itself is an assertion that cannot fail.
        VelocityWorkloadPlanner.canonicalSpec(proxyDefinition()) shouldContain "velocity.build=$fromCatalog"
    }

    /**
     * The token, as coordinates, in the only channel that is allowed to carry
     * one.
     *
     * `:core` has sent this bearer token on every control call since the kind
     * existed; the plugin was never told what it was, so it started with
     * `auth.required = false` and served whoever reached the port. The two ends
     * are now the same `SecretRef`, which is what makes the schema's
     * "`hostPort` requires `tokenSecret`" rule mean anything.
     */
    @Test
    fun `the control token travels as a coordinate in secretEnv, never in the environment`() {
        val ref = token()

        val spec = VelocityWorkloadPlanner.plan(proxyDefinition(tokenSecret = ref))

        spec.secretEnv[VelocityWorkloadPlanner.CONTROL_TOKEN] shouldBe ref
        // Not in `env`, which is rendered in logs and stored: a plain variable
        // would be the same leak the forwarding secret is kept out of.
        spec.env.containsKey(VelocityWorkloadPlanner.CONTROL_TOKEN).shouldBeFalse()

        // The control for a leak assertion: the coordinate *is* findable in the
        // rendered spec, so this cannot pass because the needle never existed.
        val rendered = spec.toString()
        rendered shouldContain VelocityWorkloadPlanner.CONTROL_TOKEN
        rendered shouldContain "<from secret store>"
        // And it renders as a reference rather than as a value — there is no
        // material in this process to render, which is the point of a coordinate.
        rendered shouldNotContain "=${ref.name}/${ref.key}"
    }

    /**
     * Absence is a configuration, not a fallback.
     *
     * The schema allows an endpoint with no token only when it is not published,
     * and the plugin reads a missing variable as "no authentication required". A
     * planner that invented a placeholder would make that agreement silently
     * false in the other direction.
     */
    @Test
    fun `a proxy that declared no token has no token variable at all`() {
        val spec = VelocityWorkloadPlanner.plan(proxyDefinition())

        spec.secretEnv[VelocityWorkloadPlanner.CONTROL_TOKEN].shouldBeNull()
        spec.secretEnv.keys shouldBe setOf(VelocityWorkloadPlanner.FORWARDING_SECRET)
        spec.env.containsKey(VelocityWorkloadPlanner.CONTROL_TOKEN).shouldBeFalse()
    }

    /**
     * The image runs BungeeCord unless it is told otherwise.
     *
     * `TYPE` defaults to `BUNGEECORD` in `itzg/mc-proxy`'s entrypoint. A proxy
     * that came up without this variable was not a Velocity proxy: modern
     * forwarding does not apply, a Velocity plugin cannot load, and the control
     * endpoint therefore does not exist — so the plugin JAR arriving correctly
     * would still have bought nothing.
     */
    @Test
    fun `the image is told which proxy to run`() {
        VelocityWorkloadPlanner.plan(proxyDefinition()).env[VelocityWorkloadPlanner.TYPE] shouldBe "VELOCITY"
    }

    /**
     * The environment is exactly what the image and the plugin read, and nothing
     * that merely looks like configuration.
     *
     * `VELOCITY_PORT` and `VELOCITY_MAX_PLAYERS` used to be here. Neither exists
     * in the image's entrypoint and neither is read by Velocity, which takes both
     * from `velocity.toml` — so a definition's `network.port` was being "applied"
     * by a variable nothing consumed, which is exactly how the wrong default port
     * survived: it looked configured. Set-membership rather than a contains
     * check, so re-adding one fails here rather than passing quietly.
     *
     * `VELOCITY_VERSION` joined the set in the same change that pinned it. It is
     * the counter-example to the rule above and belongs here for that reason: the
     * image's entrypoint genuinely reads it, and leaving it out is what let the
     * Velocity inside a running proxy be decided by upstream's release schedule.
     */
    @Test
    fun `the environment carries nothing the image does not read`() {
        val spec = VelocityWorkloadPlanner.plan(proxyDefinition())

        spec.env.keys shouldBe
            setOf(
                VelocityWorkloadPlanner.TYPE,
                VelocityWorkloadPlanner.VELOCITY_VERSION,
                VelocityWorkloadPlanner.FORWARDING_MODE,
                VelocityWorkloadPlanner.CONTROL_PORT,
                VelocityWorkloadPlanner.INIT_MEMORY,
                VelocityWorkloadPlanner.MAX_MEMORY,
            )
    }

    /**
     * The port the reconciler maps and pings is the port the proxy image binds.
     *
     * Nothing configures Velocity's `bind` — not this planner, not the image — so
     * `spec.network.port` is a claim about the image rather than a request, and
     * the mapped port has to be that claim unaltered. A literal on purpose:
     * reading the constant back would pass against any value it held, and this
     * one was changed to Velocity's own default (25565) on good evidence and cost
     * a proxy that never became ready. `VelocityProxyDefaults.PLAYER_PORT` carries
     * the whole story; the integration suite is what keeps it true.
     */
    @Test
    fun `the mapped player port is the one the proxy image listens on`() {
        val player =
            VelocityWorkloadPlanner
                .plan(proxyDefinition())
                .ports
                .single { it.name == VelocityWorkloadPlanner.PLAYER_PORT_NAME }

        player.containerPort shouldBe 25577
        player.containerPort shouldBe VelocityProxyDefaults.PLAYER_PORT
    }

    /**
     * The control port is published only when the definition asked for it.
     *
     * This port can move every player in a fleet, so the default is a port that
     * exists only inside the sandbox.
     *
     * Deliberately says nothing about the token, though the fixture declares one:
     * an assertion about the token here would make this test fail for the reason
     * the token test already covers, and two tests that redden together are two
     * tests one of which is carrying the other.
     */
    @Test
    fun `the control port is not published unless the definition published it`() {
        val spec = VelocityWorkloadPlanner.plan(proxyDefinition(tokenSecret = token()))

        val control = spec.ports.single { it.name == VelocityWorkloadPlanner.CONTROL_PORT_NAME }
        control.hostPort.shouldBeNull()
        control.containerPort shouldBe VelocityProxyDefaults.CONTROL_PORT
    }

    /** A proxy asks for one artefact. Anything else would be an artefact nobody ships. */
    @Test
    fun `a paper backend asks for no assets`() {
        mcorch.core.paper.PaperWorkloadPlanner
            .plan(mcorch.core.paperDefinition())
            .assets
            .shouldBeEmpty()
    }
}
