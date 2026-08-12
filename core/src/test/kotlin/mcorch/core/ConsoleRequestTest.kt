package mcorch.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What the node refuses to be asked, and what it refuses to print.
 *
 * The construction rules matter more here than on most request types, because
 * everything downstream treats the command as already screened. A blank or
 * multi-line command that reached `LocalNode.console` would be dispatched.
 */
internal class ConsoleRequestTest {
    private fun requestOf(
        port: Int = 25575,
        command: String = "list",
    ) = ConsoleRequest(
        port = port,
        passwordSecret = secretRef(),
        command = command,
        timeout = ExecTimeout.of(5.seconds),
    )

    @Test
    fun `a well-formed request is accepted`() {
        requestOf().command shouldBe "list"
    }

    @Test
    fun `a port outside the legal range is refused`() {
        listOf(0, -1, 65536).forEach { port ->
            shouldThrow<IllegalArgumentException> { requestOf(port = port) }
        }
        // Control: the edges of the range are legal, so this is a bound and not a
        // blanket refusal.
        requestOf(port = 1).port shouldBe 1
        requestOf(port = 65535).port shouldBe 65535
    }

    @Test
    fun `a blank command is refused`() {
        listOf("", "   ", "\t").forEach { blank ->
            shouldThrow<IllegalArgumentException> { requestOf(command = blank) }
        }
    }

    @Test
    fun `a multi-line command is refused`() {
        // A newline may carry a second command, and nothing downstream could say
        // which one a refusal or an audit record referred to.
        listOf("list\nstop", "list\r\nstop", "list\n", "\nstop").forEach { multiline ->
            shouldThrow<IllegalArgumentException> { requestOf(command = multiline) }
                .message
                .toString() shouldContain "single line"
        }
    }

    @Test
    fun `a request never prints the command it carries`() {
        // An argument can be a player name, and a request reaches log lines and
        // failure messages by way of every generic toString in the JDK.
        val rendered = requestOf(command = "kick Alice").toString()
        rendered shouldNotContain "Alice"
        rendered shouldContain "redacted"

        // Control: what is safe to print is still printed.
        rendered shouldContain "port=25575"
    }
}
