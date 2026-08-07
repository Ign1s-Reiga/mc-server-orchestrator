package mcorch.core.node

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.EndpointRequest
import mcorch.core.EndpointTimeout
import mcorch.core.EndpointTimeoutCeiling
import mcorch.core.HttpVerb
import mcorch.schema.SpecBounds
import mcorch.schema.VelocityProxyDefaults
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The third duration that becomes a transport deadline, and the bound on it.
 *
 * [EndpointTimeout] exists for the reason `StopGrace` and `ExecTimeout` do: a `Node`
 * implementation turns the argument straight into its transport's deadline, so an
 * unbounded value is a reconcile worker with no effective timeout — here on the
 * drain's own control path, where a parked worker is a backend that cannot be
 * sealed. `spec.backends.drain.sealTimeout` is what reaches it, from a reader **and**
 * from a store row.
 *
 * The unit tests are the ones that matter for a bound: a scenario exercises one
 * value, and what a ceiling has to be right about is the whole range either side of
 * it. `UnbuildableRequestTest` carries the other half — what the loop does with the
 * values this type deliberately refuses to fix.
 */
internal class EndpointTimeoutTest {
    /**
     * The ceiling is *borrowed*, not restated, and that is the property to pin.
     *
     * The claim it rests on is "no reader accepts more than this". Restating an hour
     * here would make raising `VelocityProxyReader`'s cap silently start clamping
     * definitions an operator wrote and a reader accepted; borrowing it means the two
     * move together. The second assertion is the same claim across the module
     * boundary: `SpecBounds` bounds the very same field at the decode, and if these
     * two constants ever disagree then one of the two layers is shortening a value
     * the other thinks is fine.
     */
    @Test
    fun `the ceiling is the widest value a reader accepts for this field`() {
        EndpointTimeoutCeiling.MAX shouldBe VelocityProxyDefaults.MAX_TIMEOUT
        EndpointTimeoutCeiling.MAX shouldBe SpecBounds.MAX_HANDSHAKE_TIMEOUT
        // The control: the constant is an hour today, so a test that only compared
        // it with itself would pass against a build where both had moved to zero.
        EndpointTimeoutCeiling.MAX shouldBe 1.hours
    }

    /**
     * Everything a reader would accept passes through untouched.
     *
     * The whole safety argument for a cap is that it never shortens a legitimate
     * declaration. `VelocityProxyReader` accepts `1s..1h`, so every value in that
     * range has to come out exactly as it went in — including the top of it, which is
     * the one an off-by-one in [EndpointTimeoutCeiling.bound] would clip.
     */
    @Test
    fun `a declared timeout inside the readers range is not shortened`() {
        listOf(
            1.seconds,
            VelocityProxyDefaults.SEAL_TIMEOUT,
            30.seconds,
            5.minutes,
            EndpointTimeoutCeiling.MAX,
        ).forEach { declared ->
            withClue("$declared was shortened") {
                EndpointTimeout.of(declared).period shouldBe declared
            }
        }
    }

    /**
     * Above the ceiling it caps, `Duration.INFINITE` included.
     *
     * Capping the infinite is where this differs from `StopGraceCeiling`, which
     * passes it through to be refused by name at the runtime edge — and the
     * difference is what the number authorises. A grace period authorises a *kill*,
     * so an uninterpretable value must not be made to look like a plausible one. This
     * authorises only *waiting*, and on this channel a wait cut short can do no more
     * than park a drain: every unanswered control call becomes
     * `ControlOutcome.Unavailable`, and no branch reads one as "nobody is connected"
     * or "the backend is sealed".
     */
    @Test
    fun `a timeout beyond the ceiling is capped, infinity included`() {
        listOf(
            2.hours,
            30.days,
            Duration.INFINITE,
        ).forEach { declared ->
            withClue("$declared was not capped") {
                EndpointTimeout.of(declared).period shouldBe EndpointTimeoutCeiling.MAX
            }
        }
        EndpointTimeout.of(30.days).period shouldBeLessThan 30.days
    }

    /**
     * Zero and negative are **refused**, not raised — and the refusal is what a
     * caller classifies.
     *
     * A ceiling has no answer for the bottom of the range: raising a zero into a real
     * wait would turn a value the code cannot interpret into a plausible-looking
     * call, which is exactly what the cap above refuses to do at the other end. So it
     * stays an `IllegalArgumentException` out of `EndpointRequest`'s own `init`, with
     * the offending value in the message so that what an operator reads names the
     * thing they have to change.
     *
     * Where that exception is turned into something a dashboard shows is
     * `ControlChannel.unbuildable`, and `UnbuildableRequestTest` is where that is
     * asserted. This is only the rule.
     */
    @Test
    fun `a timeout that is not a positive duration is refused with the value in the message`() {
        listOf(Duration.ZERO, (-1).seconds).forEach { declared ->
            val rejected =
                shouldThrow<IllegalArgumentException> {
                    EndpointRequest(
                        port = 8123,
                        verb = HttpVerb.GET,
                        path = "/v1/version",
                        timeout = EndpointTimeout.of(declared),
                    )
                }
            withClue("the refusal does not say what was wrong: ${rejected.message}") {
                rejected.message.orEmpty() shouldContain "endpoint timeout must be positive"
                rejected.message.orEmpty() shouldContain declared.toString()
            }
        }
    }

    /**
     * The port is a definition field too, and it is refused the same way.
     *
     * `spec.control.port` reaches `EndpointRequest` beside the timeout, from the same
     * row and with the same absence of a spec-level `init`. It is asserted here
     * because the classification at the call site catches the *construction*, not one
     * field of it — so a port of zero is recorded and reported exactly as a zero
     * timeout is, and a reader of `ControlChannel.unbuildable` needs to know its
     * message covers both.
     */
    @Test
    fun `a port outside the addressable range is refused as well`() {
        val rejected =
            shouldThrow<IllegalArgumentException> {
                EndpointRequest(
                    port = 0,
                    verb = HttpVerb.GET,
                    path = "/v1/version",
                    timeout = EndpointTimeout.of(VelocityProxyDefaults.SEAL_TIMEOUT),
                )
            }
        rejected.message.orEmpty() shouldContain "port must be in 1..65535"
    }
}
