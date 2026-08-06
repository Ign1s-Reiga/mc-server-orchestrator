package mcorch.cri.it

import kotlinx.coroutines.delay
import mcorch.cri.ContainerId
import mcorch.cri.ContainerSpec
import mcorch.cri.ContainerState
import mcorch.cri.CriClient
import mcorch.cri.CriClientConfig
import mcorch.cri.CriEndpoint
import mcorch.cri.ImageName
import mcorch.cri.LinuxSandboxSpec
import mcorch.cri.SandboxFilter
import mcorch.cri.SandboxId
import mcorch.cri.SandboxSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A real [CriClient] against this project's own containerd, plus one disposable
 * container to point it at.
 *
 * The container's init process **ignores `SIGTERM`**, which is the only way to
 * observe a grace period at all: a process that exits on the stop signal makes
 * every timeout look the same. Nothing here holds world data, so cleanup is
 * allowed to be brutal.
 *
 * Like `:app`'s harness, this never touches a system containerd or a Docker
 * socket — the endpoint defaults to the instance `scripts/dev/containerd-up.sh`
 * creates under `/run/mcorch-dev`.
 */
internal class RuntimeHarness : AutoCloseable {
    val client: CriClient = CriClient.connect(CriClientConfig(endpoint = CriEndpoint.parse(endpoint())))

    private val sandboxes = mutableListOf<SandboxId>()

    /**
     * Brings up a sandbox and a running container that will not stop when asked
     * politely, and returns its ID.
     */
    suspend fun startSigtermIgnoringContainer(): ContainerId {
        val uid = "cri-it-${System.nanoTime()}"
        val spec =
            SandboxSpec(
                name = SANDBOX_NAME,
                uid = uid,
                namespace = NAMESPACE,
                hostname = "cri-it",
                labels = mapOf(LABEL to uid),
                // Matches what LocalNodeConfig defaults to. Without it, a host
                // running the systemd cgroup driver rejects every sandbox.
                linux = LinuxSandboxSpec(cgroupParent = CGROUP_PARENT),
            )
        client.pullImage(ImageName(IMAGE))
        val sandbox = client.runSandbox(spec)
        sandboxes += sandbox
        val container =
            client.createContainer(
                sandbox,
                spec,
                ContainerSpec(
                    name = "sigterm-ignorer",
                    image = ImageName(IMAGE),
                    // `trap '' TERM` makes the shell ignore the stop signal, so
                    // containerd has to sit out the whole grace period and then
                    // SIGKILL. That wait is what these tests measure.
                    command = listOf("/bin/sh", "-c", "trap '' TERM; while true; do sleep 1; done"),
                    labels = mapOf(LABEL to uid),
                ),
            )
        client.startContainer(container)
        awaitState(container, ContainerState.RUNNING)
        // The trap is installed by the first line of the script, but "the task
        // is running" and "the shell has read its script" are not the same
        // instant, and a stop that lands in between would be served by a process
        // with default signal handling.
        delay(TRAP_SETTLE)
        return container
    }

    suspend fun state(id: ContainerId): ContainerState = client.containerStatus(id).state

    private suspend fun awaitState(
        id: ContainerId,
        state: ContainerState,
    ) {
        val deadline = System.nanoTime() + STATE_TIMEOUT.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (client.containerStatus(id).state == state) return
            delay(POLL)
        }
        error("container $id did not reach $state within $STATE_TIMEOUT; it is ${client.containerStatus(id).state}")
    }

    /**
     * Removes everything this harness created.
     *
     * `removeSandbox` force-kills whatever is still inside with no grace and no
     * save — normally the thing this module warns loudest about, and exactly
     * right here: every one of these containers is a shell loop, and a test that
     * left one running would poison the next run.
     */
    override fun close() {
        kotlinx.coroutines.runBlocking {
            for (sandbox in sandboxes) {
                runCatching { client.stopSandbox(sandbox) }
                runCatching { client.removeSandbox(sandbox) }
            }
            // Anything this suite created in an earlier, crashed run.
            runCatching {
                client
                    .listSandboxes(SandboxFilter.ALL)
                    .filter { it.metadata.namespace == NAMESPACE }
                    .forEach { leftover ->
                        System.err.println("cri integration cleanup: removing orphaned sandbox ${leftover.id}")
                        runCatching { client.stopSandbox(leftover.id) }
                        runCatching { client.removeSandbox(leftover.id) }
                    }
            }
        }
        client.close()
    }

    internal companion object {
        const val ENDPOINT_VARIABLE: String = "MCORCH_CRI_ENDPOINT"
        const val DEFAULT_ENDPOINT: String = "unix:///run/mcorch-dev/containerd.sock"
        const val NAMESPACE: String = "mcorch-cri-it"
        const val SANDBOX_NAME: String = "stop-grace-probe"
        const val LABEL: String = "mcorch.dev/cri-it"
        const val CGROUP_PARENT: String = "mcorch.slice"

        /**
         * Any image with a POSIX shell would do. This one is already in the set
         * `:app:integrationTest` pulls, so a fresh host does not download
         * anything extra for these tests.
         */
        const val IMAGE: String = "docker.io/itzg/minecraft-server:2026.6.1"

        /**
         * How long a stop is watched before it is called "still waiting".
         *
         * The values under test are decades and centuries, so anything above the
         * round-trip noise separates "containerd is serving the grace period"
         * from "containerd already killed it". Twelve seconds is long enough to
         * be unambiguous and short enough to run.
         */
        val WATCH: Duration = 12.seconds

        private val POLL = 250.milliseconds
        private val TRAP_SETTLE = 3.seconds
        private val STATE_TIMEOUT = 2.minutes

        fun endpoint(): String = System.getenv(ENDPOINT_VARIABLE)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT

        /**
         * Whether the dev containerd is actually there.
         *
         * Checked loudly rather than as a per-test assumption: a suite that skips
         * itself when the runtime is missing reports green having tested nothing,
         * and what these tests check is precisely the part no fake can answer.
         */
        fun requireContainerd() {
            val socket = endpoint().removePrefix("unix://")
            check(Files.exists(Path.of(socket))) {
                "no containerd socket at $socket. These tests run against this project's own containerd, not the " +
                    "host's: start it with scripts/dev/containerd-up.sh, or point $ENDPOINT_VARIABLE at another " +
                    "instance you are willing to have containers created in"
            }
        }
    }
}
