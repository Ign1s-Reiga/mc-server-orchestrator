package mcorch.core.node

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * What a runtime is allowed to say on a server's observed status.
 *
 * `FailureStatus.message` is written to `state.db` and served through the API,
 * so a runtime error that quotes the request it rejected puts that request at
 * rest and on the wire. Go renders structs into error strings routinely; this is
 * a promise about a third party's text, so the answer cannot be "trust it".
 *
 * The secret-shaped material is generated per test rather than written as a
 * literal — a literal in a test file is how a real one eventually ends up in
 * one.
 */
internal class RuntimeDetailTest {
    private fun material(): String =
        "s3cr3t-" +
            java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")

    @Test
    fun `a description from a request that carries secrets is not repeated at all`() {
        val secret = material()
        // What a Go runtime does when it wraps the rejected request:
        // `fmt.Errorf("invalid config: %+v", config)`.
        val rendered =
            "CREATE_CONTAINER failed (INVALID_ARGUMENT, permanent): invalid container config: " +
                "&ContainerConfig{Envs:[]&KeyValue{Key:RCON_PASSWORD,Value:$secret,}}"

        val detail =
            runtimeDetail(
                operation = "CREATE_CONTAINER",
                code = "INVALID_ARGUMENT",
                rendered = rendered,
                requestMayCarrySecrets = true,
            )

        detail shouldNotContain secret
        // Not a truncation: Go renders the request from the front, so any prefix
        // of this description is a prefix of the container's environment.
        detail shouldNotContain "ContainerConfig"
        detail shouldNotContain "RCON_PASSWORD"

        // What survives is ours, and it is enough to say what failed and how.
        detail shouldContain "CREATE_CONTAINER"
        detail shouldContain "INVALID_ARGUMENT"
        detail shouldContain "not recorded"
    }

    @Test
    fun `an exec description survives whole, because it is the diagnostic that matters`() {
        // The message that separated a slow command on a healthy node from a
        // node that had stopped answering. Losing it costs that diagnosis.
        val rendered =
            "EXEC_SYNC failed (DEADLINE_EXCEEDED, retryable): the command did not finish within the 10s " +
                "timeout it was given, and the runtime stopped it. It said: failed to exec in container: " +
                "timeout 10s exceeded: context deadline exceeded"

        val detail =
            runtimeDetail(
                operation = "EXEC_SYNC",
                code = "DEADLINE_EXCEEDED",
                rendered = rendered,
                requestMayCarrySecrets = false,
            )

        // Byte for byte: no cap, no rewrite. An argv carries no credential.
        detail shouldBe rendered
    }
}
