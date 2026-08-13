package mcorch.core.console

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.ConsoleSpec
import mcorch.schema.Tier
import org.junit.jupiter.api.Test

/**
 * The second gate, and its relationship with the first.
 *
 * The ordering assertions matter most. Gate 1 refuses what nobody may run, and a
 * tier gate that ran first — or that let the top tier skip it — would put `stop`
 * back within reach of the most trusted caller, which is exactly the shape the
 * drain protocol exists to prevent.
 */
internal class ConsolePolicyTest {
    private val open = ConsoleSpec(maxTier = Tier.SUPERUSER)

    @Test
    fun `Gate 1 runs first, and the top tier does not skip it`() {
        listOf(Tier.MEMBER, Tier.OPERATOR, Tier.SUPERUSER).forEach { tier ->
            val decision = ConsolePolicy.screen("stop", held = tier, ceiling = open)
            decision
                .shouldBeInstanceOf<ConsoleDecision.RefusedOutright>()
                .screening.reason shouldBe RefusalReason.ENDS_THE_SERVER
        }

        // save-off too, and by its own reason rather than by tier.
        ConsolePolicy
            .screen("save-off", held = Tier.SUPERUSER, ceiling = open)
            .shouldBeInstanceOf<ConsoleDecision.RefusedOutright>()
            .screening.reason shouldBe RefusalReason.DISABLES_WORLD_SAVING
    }

    @Test
    fun `Superuser is bounded by Gate 1 and nothing else`() {
        // A general console: an unknown command — a mod's, say — runs. That is the
        // whole point of the output decision, and an allow-list here would be
        // exactly what stops a Forge mod's command working.
        ConsolePolicy
            .screen("someplugin:dosomething arg", held = Tier.SUPERUSER, ceiling = open)
            .shouldBeInstanceOf<ConsoleDecision.Permitted>()
            .effective shouldBe Tier.SUPERUSER

        // And there is no finite list to render for it.
        ConsolePolicy.available(Tier.SUPERUSER, open).shouldBeNull()
    }

    @Test
    fun `the lower tiers allow-list, and an unknown command is refused there`() {
        ConsolePolicy
            .screen("list", held = Tier.MEMBER, ceiling = open)
            .shouldBeInstanceOf<ConsoleDecision.Permitted>()

        val refused =
            ConsolePolicy
                .screen("someplugin:dosomething", held = Tier.MEMBER, ceiling = open)
                .shouldBeInstanceOf<ConsoleDecision.RefusedByTier>()
        refused.effective shouldBe Tier.MEMBER
        // Nothing below Superuser holds a mod's command, and saying so is the
        // honest answer rather than naming a tier that would not help.
        refused.required shouldBe Tier.SUPERUSER

        // A Member may not moderate; an Operator may.
        ConsolePolicy
            .screen("kick Alice", held = Tier.MEMBER, ceiling = open)
            .shouldBeInstanceOf<ConsoleDecision.RefusedByTier>()
            .required shouldBe Tier.OPERATOR
        ConsolePolicy
            .screen("kick Alice", held = Tier.OPERATOR, ceiling = open)
            .shouldBeInstanceOf<ConsoleDecision.Permitted>()
    }

    @Test
    fun `save-all is permitted where save-off is refused`() {
        // The pair that proves the two gates are about different things: they sit
        // at the same Minecraft op level, and one is what the drain itself runs.
        ConsolePolicy
            .screen("save-all", held = Tier.OPERATOR, ceiling = open)
            .shouldBeInstanceOf<ConsoleDecision.Permitted>()
        ConsolePolicy
            .screen("save-off", held = Tier.OPERATOR, ceiling = open)
            .shouldBeInstanceOf<ConsoleDecision.RefusedOutright>()
    }

    @Test
    fun `the server's ceiling lowers a caller and never raises one`() {
        val restricted = ConsoleSpec(maxTier = Tier.MEMBER)

        // A Superuser is a Member here, so the refusal is the *server's* doing.
        val refused =
            ConsolePolicy
                .screen("kick Alice", held = Tier.SUPERUSER, ceiling = restricted)
                .shouldBeInstanceOf<ConsoleDecision.RefusedByTier>()
        refused.effective shouldBe Tier.MEMBER
        refused.required shouldBe Tier.OPERATOR

        // And it never grants: a Member on a server that permits Superuser is
        // still a Member.
        ConsolePolicy
            .screen("kick Alice", held = Tier.MEMBER, ceiling = open)
            .shouldBeInstanceOf<ConsoleDecision.RefusedByTier>()
        ConsolePolicy.available(Tier.MEMBER, open).shouldNotBeNull()
    }

    @Test
    fun `the default ceiling is the most restrictive`() {
        // A definition that says nothing about the console gets the least, which is
        // the same side holdsWorldData and persistent storage default to.
        ConsoleSpec().maxTier shouldBe Tier.MEMBER

        ConsolePolicy
            .screen("kick Alice", held = Tier.SUPERUSER, ceiling = ConsoleSpec())
            .shouldBeInstanceOf<ConsoleDecision.RefusedByTier>()
    }

    @Test
    fun `both gates read a verb the same way`() {
        // If the two normalisations disagreed, a command could pass the tier gate
        // as one verb and reach the server as another.
        listOf("/minecraft:KICK Alice", "  kick Alice", "//kick Alice").forEach { spelling ->
            ConsolePolicy
                .screen(spelling, held = Tier.OPERATOR, ceiling = open)
                .shouldBeInstanceOf<ConsoleDecision.Permitted>()
            ConsolePolicy
                .screen(spelling, held = Tier.MEMBER, ceiling = open)
                .shouldBeInstanceOf<ConsoleDecision.RefusedByTier>()
        }
    }
}
