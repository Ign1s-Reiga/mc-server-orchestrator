package mcorch.cri

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * Runs a suspending test body and pins the enclosing test method's return type
 * to `Unit`.
 *
 * `fun t() = runBlocking { ... }` infers whatever the block's last expression
 * returns, and JUnit Jupiter does not treat a method with a non-void return type
 * as a test at all — it is dropped at discovery with only a warning, so the
 * suite goes green having run nothing. Every suspending test here goes through
 * this so that cannot happen.
 *
 * Real `runBlocking`, not `runTest`: gRPC deadlines are enforced against the
 * wall clock, so a virtual-time dispatcher would not exercise them.
 */
internal fun runCriTest(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking(block = body)

/**
 * Shared, deliberately boring specs.
 *
 * Nothing here carries a Velocity forwarding secret, a player name or a UUID —
 * a fixture is exactly the kind of file where such a value gets committed by
 * accident and then lives forever.
 */
internal fun sampleSandboxSpec(
    name: String = "survival-1",
    labels: Map<String, String> = mapOf("mcorch.dev/server" to "survival-1"),
): SandboxSpec =
    SandboxSpec(
        name = name,
        uid = "sandbox-uid-$name",
        namespace = "mcorch",
        hostname = name,
        logDirectory = "/var/log/mcorch/$name",
        portMappings = listOf(PortMapping(containerPort = 25565, hostPort = 25565)),
        labels = labels,
    )

internal fun sampleContainerSpec(
    name: String = "paper",
    image: ImageName = ImageName("docker.io/itzg/minecraft-server:latest"),
    env: Map<String, String> = mapOf("EULA" to "TRUE", "TYPE" to "PAPER"),
): ContainerSpec =
    ContainerSpec(
        name = name,
        image = image,
        env = env,
        mounts =
            listOf(
                VolumeMount(containerPath = "/data", hostPath = "/var/lib/mcorch/worlds/survival-1"),
            ),
        labels = mapOf("mcorch.dev/server" to "survival-1"),
    )
