package mcorch.schema

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import mcorch.schema.fixtures.ExampleDefinitions
import mcorch.schema.yaml.ServerDefinitionParser
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The ceiling that applies to a definition which did not come through a reader.
 *
 * Three durations here become transport deadlines and nothing but a YAML reader
 * bounds them, so a definition rebuilt from a stored row can park a reconcile
 * worker with no effective timeout. [SpecBounds] is the one place that is closed,
 * and these are the properties the close depends on.
 */
class SpecBoundsTest {
    private fun paper(): PaperServerDefinition =
        ServerDefinitionParser
            .parse(ExampleDefinitions.valid("full.yaml"), "full.yaml")
            .getOrThrow()
            .shouldBeInstanceOf<PaperServerDefinition>()

    private fun proxy(): VelocityProxyDefinition =
        ServerDefinitionParser
            .parse(ExampleDefinitions.valid("proxy-full.yaml"), "proxy-full.yaml")
            .getOrThrow()
            .shouldBeInstanceOf<VelocityProxyDefinition>()

    private fun PaperServerDefinition.withLifecycle(
        stopGracePeriod: Duration,
        saveTimeout: Duration,
    ): PaperServerDefinition =
        copy(
            spec =
                spec.copy(
                    lifecycle =
                        spec.lifecycle.copy(
                            drain = spec.lifecycle.drain.copy(saveTimeout = saveTimeout),
                            stopGracePeriod = stopGracePeriod,
                        ),
                ),
        )

    private fun BoundedDefinition.paperSpec(): PaperServerSpec = definition.spec.shouldBeInstanceOf<PaperServerSpec>()

    private fun BoundedDefinition.proxySpec(): VelocityProxySpec =
        definition.spec.shouldBeInstanceOf<VelocityProxySpec>()

    // ------------------------------------------------------- the ordinary path

    /**
     * The reconcile loop reads the whole fleet every resync, so the healthy path
     * has to be free. Same instance, not merely an equal one: a copy per row per
     * pass is the cost this bound must not have.
     */
    @Test
    fun `a definition a reader produced is returned untouched and uncopied`() {
        for (definition in listOf<ServerDefinition>(paper(), proxy())) {
            val bounded = SpecBounds.bound(definition)

            bounded.definition shouldBeSameInstanceAs definition
            bounded.clamped.shouldBeEmpty()
            bounded.wasClamped shouldBe false
        }
    }

    @Test
    fun `every valid example is already inside every ceiling`() {
        // If an example ever needed clamping, either the example is wrong or a
        // ceiling has been set below something an operator is invited to copy.
        // `parseAll`, because one of the examples is a multi-document file.
        for (name in ExampleDefinitions.names("valid")) {
            for (definition in ServerDefinitionParser.parseAll(ExampleDefinitions.valid(name), name).getOrThrow()) {
                SpecBounds.bound(definition).clamped.shouldBeEmpty()
            }
        }
    }

    // ------------------------------------------------------------ Paper server

    @Test
    fun `a save timeout above the ceiling is capped and reported`() {
        val definition = paper().withLifecycle(stopGracePeriod = 30.hours, saveTimeout = 20.hours)

        val bounded = SpecBounds.bound(definition)

        bounded
            .paperSpec()
            .lifecycle.drain.saveTimeout shouldBe SpecBounds.MAX_SAVE_TIMEOUT
        bounded.clamped
            .single { it.field == "spec.lifecycle.drain.saveTimeout" }
            .declared shouldBe 20.hours
    }

    @Test
    fun `a stop grace period above the ceiling is capped and reported`() {
        val definition = paper().withLifecycle(stopGracePeriod = 30.hours, saveTimeout = 20.hours)

        val bounded = SpecBounds.bound(definition)

        bounded.paperSpec().lifecycle.stopGracePeriod shouldBe SpecBounds.MAX_STOP_GRACE_PERIOD
        val clamp = bounded.clamped.single { it.field == "spec.lifecycle.stopGracePeriod" }
        clamp.declared shouldBe 30.hours
        clamp.applied shouldBe SpecBounds.MAX_STOP_GRACE_PERIOD
    }

    /**
     * The thirtieth drain audit's finding, moved to where both halves are in hand.
     *
     * `stopGracePeriod` and `saveTimeout` are a validated pair, and a ceiling
     * applied to one half by something that cannot see the other half inverts it —
     * a container SIGKILLed part-way through Paper's shutdown save, which is a torn
     * region file. Every pair the schema accepts has to still satisfy
     * [SpecInvariants.stopGraceProblem] after the clamp.
     */
    @Test
    fun `clamping never inverts the stop grace invariant`() {
        val margin = PaperServerDefaults.MIN_STOP_GRACE_MARGIN
        val saveTimeouts = listOf(1.seconds, 30.minutes, 1.hours, 3.hours, 20.hours, 400.hours)
        val extras = listOf(margin, 1.hours, 5.hours, 300.hours)

        for (saveTimeout in saveTimeouts) {
            for (extra in extras) {
                val definition =
                    paper().withLifecycle(stopGracePeriod = saveTimeout + extra, saveTimeout = saveTimeout)

                val spec = SpecBounds.bound(definition).paperSpec()

                // Stated as the schema states it, so the two cannot drift: the
                // rebuilt spec would have thrown from `LifecycleSpec.init` if this
                // were violated, and asserting it here says which property that is.
                (spec.lifecycle.stopGracePeriod >= spec.lifecycle.drain.saveTimeout + margin) shouldBe true
            }
        }
    }

    /**
     * The lower half of the reader's range is deliberately not reproduced. A floor
     * on the save timeout *raises* the minimum the grace period must clear, so it
     * can break a pair that was perfectly legal on disk — the exact inversion the
     * ceiling above is written to avoid, arriving from the other direction.
     */
    @Test
    fun `a save timeout below the readers minimum is left alone`() {
        val definition = paper().withLifecycle(stopGracePeriod = 30.seconds, saveTimeout = Duration.ZERO)

        val bounded = SpecBounds.bound(definition)

        bounded.clamped.shouldBeEmpty()
        bounded
            .paperSpec()
            .lifecycle.drain.saveTimeout shouldBe Duration.ZERO
    }

    /**
     * `startupTimeout` and `playerTransferTimeout` are wall-clock comparisons, not
     * deadlines on a call, so an absurd value there parks nothing. They were
     * examined and cleared, and this is the guard against someone tidying them in.
     */
    @Test
    fun `the two fields that are not deadlines are not bounded`() {
        val definition =
            paper().let {
                it.copy(
                    spec =
                        it.spec.copy(
                            lifecycle =
                                it.spec.lifecycle.copy(
                                    drain =
                                        it.spec.lifecycle.drain
                                            .copy(playerTransferTimeout = 40.hours),
                                    startupTimeout = 50.hours,
                                ),
                        ),
                )
            }

        val bounded = SpecBounds.bound(definition)

        bounded.clamped.shouldBeEmpty()
        bounded.paperSpec().lifecycle.startupTimeout shouldBe 50.hours
        bounded
            .paperSpec()
            .lifecycle.drain.playerTransferTimeout shouldBe 40.hours
    }

    // --------------------------------------------------------- Velocity proxy

    /**
     * The field the auditor traced to a blocking, uncancellable `httpClient.send`
     * inside `Dispatchers.IO`, and its two siblings — same record, same reader
     * bound, the next two steps of the same handshake. Bounding one of three
     * identically-shaped fields is how this defect recurred three rounds running.
     */
    @Test
    fun `the proxy handshake timeouts are capped and reported`() {
        val definition = proxy()
        val over =
            definition.copy(
                spec =
                    definition.spec.copy(
                        backends =
                            definition.spec.backends.copy(
                                drain =
                                    definition.spec.backends.drain
                                        .copy(
                                            sealTimeout = 30.hours,
                                            destinationTimeout = 40.hours,
                                            deregisterTimeout = 50.hours,
                                        ),
                            ),
                    ),
            )

        val bounded = SpecBounds.bound(over)

        val drain = bounded.proxySpec().backends.drain
        drain.sealTimeout shouldBe SpecBounds.MAX_HANDSHAKE_TIMEOUT
        drain.destinationTimeout shouldBe SpecBounds.MAX_HANDSHAKE_TIMEOUT
        drain.deregisterTimeout shouldBe SpecBounds.MAX_HANDSHAKE_TIMEOUT
        bounded.clamped.map { it.field } shouldBe
            listOf(
                "spec.backends.drain.sealTimeout",
                "spec.backends.drain.destinationTimeout",
                "spec.backends.drain.deregisterTimeout",
            )
    }

    /**
     * The rebuild carries every field it did not clamp — including the ones added
     * after this was written.
     *
     * `boundProxy` rebuilt [BackendDrainSpec] by naming all three of its fields,
     * which is correct while there are three and stops being correct on the day
     * there are four: the new field would be reset to its default on every proxy row
     * where any of these clamps fired. No compile error, no other failing test, and
     * a behaviour change on precisely the population a clamp selects for — the "one
     * of three identically-shaped siblings" recurrence [SpecBounds] exists to end,
     * reproduced inside it.
     *
     * Reflection over the record's own fields is what lets this case cover a field
     * nobody has written yet; a list of names here would be maintained by exactly
     * the change that needs catching. The fixture is `proxy-full.yaml` because
     * "every field a VelocityProxy accepts, set explicitly" is a promise that file
     * already makes, and the first guard below is what holds it to it.
     */
    @Test
    fun `a clamp carries every backend drain field it did not clamp`() {
        val definition = proxy()
        val declared = definition.spec.backends.drain

        // Vacuity guard one. A field the fixture leaves at its default is a field
        // this case cannot see dropped, because a dropped field lands on that same
        // default. So a field arriving without a value in proxy-full.yaml fails here
        // rather than being waved through as covered.
        BACKEND_DRAIN_FIELDS.shouldNotBeEmpty()
        val defaults = BackendDrainSpec().fieldValues()
        for ((name, value) in declared.fieldValues()) {
            withClue(
                "proxy-full.yaml sets every field a VelocityProxy accepts, but " +
                    "spec.backends.drain.$name is at its default there, so a rebuild " +
                    "that dropped it would look identical to one that carried it",
            ) {
                value shouldNotBe defaults.getValue(name)
            }
        }

        val over =
            definition.copy(
                spec =
                    definition.spec.copy(
                        backends =
                            definition.spec.backends.copy(
                                drain = declared.copy(sealTimeout = 30.hours),
                            ),
                    ),
            )

        val bounded = SpecBounds.bound(over)

        // Vacuity guard two, and the load-bearing one: `bound` returns its argument
        // untouched when nothing needed moving, and an untouched argument satisfies
        // the assertion below for free. Exactly one field clamped is what makes this
        // a statement about the rebuild.
        bounded.clamped.single().field shouldBe "spec.backends.drain.sealTimeout"

        // The record the fixture declared, with the one clamped field replaced and
        // nothing else moved.
        bounded
            .proxySpec()
            .backends.drain
            .fieldValues() shouldBe
            declared.copy(sealTimeout = SpecBounds.MAX_HANDSHAKE_TIMEOUT).fieldValues()
    }

    @Test
    fun `the proxy stop grace period is capped at what its own reader accepts`() {
        val definition = proxy()
        val over =
            definition.copy(
                spec = definition.spec.copy(lifecycle = definition.spec.lifecycle.copy(stopGracePeriod = 9.hours)),
            )

        val bounded = SpecBounds.bound(over)

        bounded.proxySpec().lifecycle.stopGracePeriod shouldBe SpecBounds.MAX_PROXY_STOP_GRACE_PERIOD
        bounded.clamped.single().field shouldBe "spec.lifecycle.stopGracePeriod"
    }

    /**
     * The proxy's own `lifecycle.drain.sealTimeout` is read as
     * `ProxyDrainSubject.playerTransferTimeout` — a wall-clock comparison, cleared
     * with the other two. Its name makes it the field most likely to be swept in
     * beside `backends.drain.sealTimeout`, which is why it has a test of its own.
     */
    @Test
    fun `the proxys own drain seal timeout is not bounded`() {
        val definition = proxy()
        val over =
            definition.copy(
                spec =
                    definition.spec.copy(
                        lifecycle =
                            definition.spec.lifecycle.copy(
                                drain =
                                    definition.spec.lifecycle.drain
                                        .copy(sealTimeout = 12.hours),
                            ),
                    ),
            )

        val bounded = SpecBounds.bound(over)

        bounded.clamped.shouldBeEmpty()
        bounded
            .proxySpec()
            .lifecycle.drain.sealTimeout shouldBe 12.hours
    }

    // ------------------------------------------------------------- non-finite

    /**
     * The two shapes of ceiling, mirroring the two `:core` already applies at the
     * `Node` boundary. A wait is always safe to cut short, so `INFINITE` is capped
     * there; a stop grace period is not, so `INFINITE` stays a refusal for
     * `StopGracePeriod.of` to make by name rather than becoming a plausible-looking
     * two-hour stop.
     *
     * Unreachable from a stored row — the document format refuses to write a
     * non-finite duration — so this pins the answer for anything else that holds
     * definitions as objects.
     */
    @Test
    fun `an infinite wait is capped and an infinite stop grace period is not`() {
        val definition = paper().withLifecycle(stopGracePeriod = Duration.INFINITE, saveTimeout = Duration.INFINITE)

        val spec = SpecBounds.bound(definition).paperSpec()

        spec.lifecycle.drain.saveTimeout shouldBe SpecBounds.MAX_SAVE_TIMEOUT
        spec.lifecycle.stopGracePeriod shouldBe Duration.INFINITE
    }

    // ---------------------------------------------------------- the constants

    /**
     * Every ceiling is *borrowed* from the widest value a reader accepts rather
     * than restated, so raising a reader's cap moves it instead of silently making
     * the cap bite. Asserted against the constants the readers actually use.
     */
    @Test
    fun `the ceilings are the readers own caps`() {
        SpecBounds.MAX_STOP_GRACE_PERIOD shouldBe PaperServerDefaults.MAX_STOP_GRACE_PERIOD
        SpecBounds.MAX_SAVE_TIMEOUT shouldBe PaperServerDefaults.MAX_TIMEOUT
        SpecBounds.MAX_PROXY_STOP_GRACE_PERIOD shouldBe VelocityProxyDefaults.MAX_TIMEOUT
        SpecBounds.MAX_HANDSHAKE_TIMEOUT shouldBe VelocityProxyDefaults.MAX_TIMEOUT
    }
}

/**
 * Every declared instance field of [BackendDrainSpec], discovered rather than
 * listed.
 *
 * `Duration` is a value class, so at this level these are `long`s. Nothing here
 * interprets one — the values are only ever compared against other values read
 * the same way, so the encoding does not have to be known.
 */
private val BACKEND_DRAIN_FIELDS: List<Field> =
    BackendDrainSpec::class.java.declaredFields
        .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        .onEach { it.isAccessible = true }

private fun BackendDrainSpec.fieldValues(): Map<String, Any?> =
    BACKEND_DRAIN_FIELDS.associate { it.name to it.get(this) }
