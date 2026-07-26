package mcorch.cri

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Endpoint parsing accepts the forms containerd tooling uses. */
class CriEndpointTest {
    @Test
    fun `parses the unix socket forms containerd tooling emits`() {
        CriEndpoint.parse("unix:///run/mcorch-dev/containerd.sock") shouldBe
            CriEndpoint.UnixSocket("/run/mcorch-dev/containerd.sock")
        CriEndpoint.parse("unix:/run/mcorch-dev/containerd.sock") shouldBe
            CriEndpoint.UnixSocket("/run/mcorch-dev/containerd.sock")
        CriEndpoint.parse("/run/mcorch-dev/containerd.sock") shouldBe
            CriEndpoint.UnixSocket("/run/mcorch-dev/containerd.sock")
        CriEndpoint.parse("  unix:///run/x.sock  ") shouldBe CriEndpoint.UnixSocket("/run/x.sock")
    }

    @Test
    fun `parses tcp endpoints, including ipv6 hosts`() {
        CriEndpoint.parse("tcp://127.0.0.1:10010") shouldBe CriEndpoint.Tcp("127.0.0.1", 10010)
        CriEndpoint.parse("tcp://[::1]:10010") shouldBe CriEndpoint.Tcp("[::1]", 10010)
    }

    @Test
    fun `rejects endpoints that would silently connect to the wrong thing`() {
        shouldThrow<IllegalArgumentException> { CriEndpoint.parse("") }
        shouldThrow<IllegalArgumentException> { CriEndpoint.parse("http://localhost:8080") }
        shouldThrow<IllegalArgumentException> { CriEndpoint.parse("relative/path.sock") }
        shouldThrow<IllegalArgumentException> { CriEndpoint.parse("unix://relative.sock") }
        shouldThrow<IllegalArgumentException> { CriEndpoint.parse("tcp://localhost") }
        shouldThrow<IllegalArgumentException> { CriEndpoint.parse("tcp://localhost:not-a-port") }
        shouldThrow<IllegalArgumentException> { CriEndpoint.parse("tcp://localhost:0") }
    }

    @Test
    fun `an unset environment variable resolves to null rather than to a default socket`() {
        // Falling back silently would let a misconfigured deployment talk to the
        // dev containerd.
        CriEndpoint.fromEnvironment { null }.shouldBeNull()
        CriEndpoint.fromEnvironment { "  " }.shouldBeNull()
        CriEndpoint.fromEnvironment { "unix:///run/mcorch-dev/containerd.sock" } shouldBe
            CriEndpoint.devDefault()
    }

    @Test
    fun `the dev default matches the socket the dev script creates`() {
        // scripts/dev/containerd-env.sh: SOCKET="/run/mcorch-dev/containerd.sock"
        CriEndpoint.DEV_SOCKET_PATH shouldBe "/run/mcorch-dev/containerd.sock"
        CriEndpoint.devDefault().description shouldBe "unix:///run/mcorch-dev/containerd.sock"
        CriEndpoint.ENDPOINT_ENV_VAR shouldBe "CONTAINER_RUNTIME_ENDPOINT"
    }
}
