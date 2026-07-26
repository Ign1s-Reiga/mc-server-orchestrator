package mcorch.cri

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Specs reject configurations CRI documents as errors, at construction time
 * rather than as an opaque `INVALID_ARGUMENT` several seconds later.
 */
class SpecValidationTest {
    @Test
    fun `a sandbox needs a hostname unless it uses the host network namespace`() {
        shouldThrow<IllegalArgumentException> {
            SandboxSpec(name = "s", uid = "u", namespace = "n", hostname = "")
        }

        shouldNotThrowAny {
            SandboxSpec(
                name = "s",
                uid = "u",
                namespace = "n",
                hostname = "",
                linux =
                    LinuxSandboxSpec(
                        securityContext =
                            LinuxSandboxSecurityContext(
                                namespaces = NamespaceSpec(network = NamespaceMode.NODE),
                            ),
                    ),
            )
        }
    }

    @Test
    fun `network and ipc namespaces reject CONTAINER, which CRI does not accept`() {
        shouldThrow<IllegalArgumentException> { NamespaceSpec(network = NamespaceMode.CONTAINER) }
        shouldThrow<IllegalArgumentException> { NamespaceSpec(ipc = NamespaceMode.CONTAINER) }
        shouldNotThrowAny { NamespaceSpec(pid = NamespaceMode.CONTAINER) }
    }

    @Test
    fun `runAsGroup without a user is rejected, as CRI specifies the runtime must error`() {
        shouldThrow<IllegalArgumentException> { LinuxSecurityContext(runAsGroup = 1000) }
        shouldThrow<IllegalArgumentException> { LinuxSandboxSecurityContext(runAsGroup = 1000) }
        shouldNotThrowAny { LinuxSecurityContext(runAsUser = 1000, runAsGroup = 1000) }
        shouldNotThrowAny { LinuxSecurityContext(runAsUsername = "minecraft", runAsGroup = 1000) }
    }

    @Test
    fun `runAsUser and runAsUsername are mutually exclusive`() {
        shouldThrow<IllegalArgumentException> {
            LinuxSecurityContext(runAsUser = 1000, runAsUsername = "minecraft")
        }
    }

    @Test
    fun `mounts must be absolute, and recursive read-only implies read-only and private propagation`() {
        shouldThrow<IllegalArgumentException> { VolumeMount(containerPath = "data", hostPath = "/host") }
        shouldThrow<IllegalArgumentException> { VolumeMount(containerPath = "/data", hostPath = "host") }
        shouldThrow<IllegalArgumentException> {
            VolumeMount(containerPath = "/data", hostPath = "/host", recursiveReadOnly = true)
        }
        shouldThrow<IllegalArgumentException> {
            VolumeMount(
                containerPath = "/data",
                hostPath = "/host",
                readOnly = true,
                recursiveReadOnly = true,
                propagation = MountPropagation.BIDIRECTIONAL,
            )
        }
        shouldNotThrowAny {
            VolumeMount(containerPath = "/data", hostPath = "/host", readOnly = true, recursiveReadOnly = true)
        }
    }

    @Test
    fun `exec streams must attach something, and a tty cannot also take stderr`() {
        shouldThrow<IllegalArgumentException> {
            ExecStreams(stdin = false, stdout = false, stderr = false)
        }
        shouldThrow<IllegalArgumentException> { ExecStreams(tty = true, stderr = true) }
        shouldNotThrowAny { ExecStreams.INTERACTIVE_TTY }
        shouldNotThrowAny { ExecStreams.OUTPUT_ONLY }
    }

    @Test
    fun `identifiers reject blanks rather than silently addressing nothing`() {
        shouldThrow<IllegalArgumentException> { ContainerId("") }
        shouldThrow<IllegalArgumentException> { SandboxId("  ") }
        shouldThrow<IllegalArgumentException> { ImageName("") }
        shouldThrow<IllegalArgumentException> { ImageId("") }
    }

    @Test
    fun `port mappings are range-checked`() {
        shouldThrow<IllegalArgumentException> { PortMapping(containerPort = 0) }
        shouldThrow<IllegalArgumentException> { PortMapping(containerPort = 70000) }
        shouldThrow<IllegalArgumentException> { PortMapping(containerPort = 25565, hostPort = -1) }
        // hostPort 0 has an explicit CRI meaning: do not publish.
        shouldNotThrowAny { PortMapping(containerPort = 25565, hostPort = 0) }
    }

    @Test
    fun `a container spec never stringifies its environment values`() {
        val spec =
            sampleContainerSpec(
                env = mapOf("VELOCITY_FORWARDING_SECRET" to "should-never-appear", "EULA" to "TRUE"),
            )

        val rendered = spec.toString()

        rendered shouldNotContain "should-never-appear"
        rendered shouldNotContain "TRUE"
        rendered shouldContain "VELOCITY_FORWARDING_SECRET"
        rendered shouldContain "redacted"
    }

    @Test
    fun `registry credentials and host addresses are never stringified`() {
        RegistryAuth(
            username = "bot",
            password = "placeholder-not-a-real-credential",
            serverAddress = "registry.example",
        ).toString().let {
            it shouldNotContain "placeholder-not-a-real-credential"
            it shouldContain "registry.example"
        }

        PortMapping(containerPort = 25565, hostPort = 25565, hostIp = "10.87.0.5").toString() shouldNotContain
            "10.87.0.5"

        DnsConfig(servers = listOf("10.87.0.1")).toString() shouldNotContain "10.87.0.1"
    }

    @Test
    fun `client timeouts must all be positive`() {
        shouldThrow<IllegalArgumentException> { CriTimeouts(query = kotlin.time.Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { CriTimeouts(deadlineSlack = kotlin.time.Duration.ZERO) }
    }

    @Test
    fun `the inbound message limit cannot be set below an ExecSync response`() {
        shouldThrow<IllegalArgumentException> {
            CriClientConfig(endpoint = CriEndpoint.devDefault(), maxInboundMessageSizeBytes = 4 * 1024 * 1024)
        }
    }
}
