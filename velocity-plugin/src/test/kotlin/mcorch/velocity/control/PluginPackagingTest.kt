package mcorch.velocity.control

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The descriptor and the configuration: the two ways this plugin fails without a
 * single class of it being wrong.
 *
 * Velocity finds the plugin through `velocity-plugin.json`, which is checked in
 * rather than generated — the upstream generator is a Java annotation processor
 * and running one over a Kotlin module means adding kapt to produce eleven lines
 * of JSON. The cost of checking it in is that it can drift from the code, and
 * this is what stops it: every field is asserted against the constant it must
 * agree with, and the main class is loaded rather than merely compared.
 */
class PluginPackagingTest {
    private fun descriptor(): JsonObject {
        val raw =
            checkNotNull(javaClass.classLoader.getResourceAsStream(ControlProtocol.DESCRIPTOR_RESOURCE)) {
                "${ControlProtocol.DESCRIPTOR_RESOURCE} is not on the classpath; Velocity would not see this JAR as a plugin"
            }
        return Json.parseObject(raw.use { String(it.readAllBytes(), Charsets.UTF_8) })
    }

    @Test
    fun `the descriptor says what the code says`() {
        val descriptor = descriptor()

        descriptor.string("id") shouldBe ControlProtocol.PLUGIN_ID
        descriptor.string("name") shouldBe ControlProtocol.PLUGIN_NAME
        descriptor.string("version") shouldBe ControlProtocol.PLUGIN_VERSION
        descriptor.string("main") shouldBe ControlProtocol.PLUGIN_MAIN_CLASS
    }

    @Test
    fun `the main class the descriptor names is a class that exists`() {
        // Comparing two strings only proves they match. Loading it proves Velocity
        // will find something at that name — the failure this catches is a package
        // rename that updated the constant and left the class behind.
        val loaded = Class.forName(descriptor().string("main"))

        loaded.simpleName shouldBe "VelocityControlPlugin"
    }

    @Test
    fun `the plugin id is one Velocity will accept`() {
        // Velocity requires [a-z][a-z0-9-_]{0,63}. An id it rejects is a plugin that
        // does not load, reported as an unreachable control endpoint.
        val allowed = Regex("[a-z][a-z0-9-_]{0,63}")

        allowed.matches(ControlProtocol.PLUGIN_ID) shouldBe true
    }

    @Test
    fun `the environment is read strictly, because a wrong port is a permanently unreachable endpoint`() {
        val defaults = ControlConfig.fromEnvironment { null }
        defaults.port shouldBe ControlConfig.DEFAULT_PORT
        defaults.bindAddress shouldBe ControlConfig.DEFAULT_BIND
        defaults.token shouldBe null

        val configured =
            ControlConfig.fromEnvironment { name ->
                when (name) {
                    ControlConfig.ENV_PORT -> " 9000 "
                    ControlConfig.ENV_BIND -> "127.0.0.1"
                    ControlConfig.ENV_TOKEN -> "a-token"
                    else -> null
                }
            }
        configured shouldBe ControlConfig(port = 9000, bindAddress = "127.0.0.1", token = "a-token")

        // Not a fall back to the default: a proxy listening somewhere other than
        // where :core looks reads as unreachable forever, and the error for that
        // surfaces nowhere near the typo that caused it.
        for (bad in listOf("nope", "0", "-1", "70000", "8375x")) {
            shouldThrow<IllegalArgumentException> {
                ControlConfig.fromEnvironment { if (it == ControlConfig.ENV_PORT) bad else null }
            }.message.orEmpty() shouldContain ControlConfig.ENV_PORT
        }
    }

    @Test
    fun `the default control port is the one the schema declares`() {
        // VelocityProxyDefaults.CONTROL_PORT. :schema is not a dependency of this
        // module by design, so the agreement is pinned here rather than compiled.
        ControlConfig.DEFAULT_PORT shouldBe 8375
    }

    @Test
    fun `the default bind is the wildcard, because the sandbox is the boundary`() {
        // :core reaches this endpoint from outside the container through the Node
        // abstraction. Binding to loopback would make the unpublished case
        // unreachable rather than safe.
        ControlConfig.DEFAULT_BIND shouldBe "0.0.0.0"
    }
}
