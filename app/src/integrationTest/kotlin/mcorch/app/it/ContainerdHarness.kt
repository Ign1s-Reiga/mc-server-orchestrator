package mcorch.app.it

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mcorch.app.Orchestrator
import mcorch.app.OrchestratorConfig
import mcorch.core.Node
import mcorch.core.ReconcileLoopConfig
import mcorch.core.ReconcilerConfig
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadState
import mcorch.schema.NodeName
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.schema.ServerDefinition
import mcorch.store.SecretValue
import mcorch.store.getOrThrow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A real orchestrator against this project's own containerd.
 *
 * Everything below goes through the objects `main` builds — the store, the node
 * registry, the reconcile loop — because a harness that reached past them would
 * be testing a program nobody runs.
 *
 * ## What this will not do
 *
 * It never touches a system containerd or a Docker socket. The endpoint comes
 * from [ENDPOINT_VARIABLE] and defaults to the instance
 * `scripts/dev/containerd-up.sh` creates, which lives under `/run/mcorch-dev`
 * and shares nothing with the host's own runtime.
 */
internal class ContainerdHarness(
    root: Path,
) : AutoCloseable {
    private val nodeName: NodeName = NodeName.of("integration").getOrThrow()

    private val orchestrator: Orchestrator =
        Orchestrator.open(
            config =
                OrchestratorConfig(
                    nodeName = nodeName,
                    runtimeEndpoint = endpoint(),
                    dataDirectory = root.resolve("data").createDirectories(),
                    // World-writable: containerd runs as root and the server
                    // inside the container runs as an unprivileged user, so a
                    // JVM temp directory's default 0700 leaves the world
                    // unwritable from inside the container.
                    volumeRoot = shared(root.resolve("volumes")),
                    logRoot = shared(root.resolve("logs")),
                    sandboxNamespace = SANDBOX_NAMESPACE,
                ),
            // Tight enough that a test does not spend its life waiting on a
            // poll, and still the cadence production uses.
            reconcilerConfig = ReconcilerConfig(readinessPollInterval = 2.seconds),
            loopConfig =
                ReconcileLoopConfig(
                    resyncPeriod = 30.seconds,
                    changePollInterval = 200.milliseconds,
                    stepInterval = 500.milliseconds,
                    concurrency = 2,
                ),
        )

    val store = orchestrator.store

    private var loop: Job? = null

    /** Starts the reconcile loop in [scope]. The tests drive it exactly as `main` does. */
    fun start(scope: CoroutineScope) {
        loop = scope.launch { orchestrator.run() }
    }

    suspend fun declare(definition: ServerDefinition): ResourceName {
        store.putDefinition(definition).getOrThrow()
        return definition.metadata.name
    }

    /** Puts an RCON password in the secret store. It never appears in a definition. */
    suspend fun putSecret(
        ref: SecretRef,
        value: String,
    ) {
        orchestrator.secrets.put(ref, SecretValue.ofString(value))
    }

    suspend fun status(name: ResourceName): PaperServerStatus? =
        store.getServer(name)?.status?.status as? PaperServerStatus

    suspend fun node(): Node =
        orchestrator.nodes.node(nodeName)
            ?: error("the node this harness registered is not in its own registry")

    suspend fun observe(name: ResourceName): WorkloadObservation = node().observe(name)

    /**
     * Waits for [condition] to hold, polling the store.
     *
     * Polls rather than watches on purpose: what a test asserts is what an
     * operator would see through the API, which is the recorded status and
     * nothing else.
     */
    suspend fun await(
        what: String,
        timeout: Duration = READY_TIMEOUT,
        condition: suspend () -> Boolean,
    ) {
        val startedAt = System.nanoTime()
        var lastReport = startedAt
        val reached =
            withTimeoutOrNull(timeout) {
                while (!condition()) {
                    // Says what it is waiting on while it waits. A suite that
                    // goes quiet for five minutes and then says "timed out"
                    // tells you nothing about which of the twenty things in
                    // flight was the one that never happened.
                    if (System.nanoTime() - lastReport > REPORT_EVERY_NANOS) {
                        lastReport = System.nanoTime()
                        println("waiting ${elapsed(startedAt)}s for $what — ${snapshot()}")
                    }
                    delay(POLL)
                }
                true
            }
        checkNotNull(reached) { "timed out after $timeout waiting for: $what — ${snapshot()}" }
    }

    /** What every server the store knows about looks like right now, for a wait message. */
    private suspend fun snapshot(): String =
        runCatching {
            store
                .listServers()
                .joinToString("; ") { server ->
                    val status = server.status?.status as? PaperServerStatus
                    val drain = status?.drain
                    "${server.name}: phase=${status?.phase} ready=${status?.ready} " +
                        "drain=${drain?.state}${drain?.failure?.let { " (${it.reason})" } ?: ""}"
                }.ifEmpty { "no servers are stored" }
        }.getOrElse { "the store did not answer: ${it.message}" }

    private fun elapsed(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000_000

    /**
     * Removes everything this harness created, whatever state the test left it
     * in.
     *
     * Cleanup goes through the [Node] like everything else, so a running
     * container is stopped before it is removed — with a grace period, because
     * even a test server is a server. A test that fails half-way through a drain
     * leaves a container running, and leaving it there would poison the next
     * run.
     */
    override fun close() {
        val declared = mutableListOf<ResourceName>()
        runBlocking {
            loop?.cancel()
            loop?.join()
            declared += runCatching { store.listServers().map { it.name } }.getOrDefault(emptyList())
            val node = runCatching { node() }.getOrNull()
            if (node != null) {
                for (name in declared) runCatching { scrub(node, name) }
            }
        }
        orchestrator.close()
        sweep(declared)
    }

    /**
     * Last resort: anything the orchestrator left behind is removed by force,
     * loudly.
     *
     * Deliberately after [scrub] rather than instead of it. Cleaning up quietly
     * would hide the failure this is reporting — a workload the loop was
     * supposed to remove and did not — while leaving it in place would poison
     * every later run against this host. So it does both: says exactly what was
     * orphaned, then removes it.
     */
    private fun sweep(declared: List<ResourceName>) {
        for (name in declared + PROBE_NAMES) {
            val pods = runCatching { Crictl.sandboxes(name.value) }.getOrDefault(emptyList())
            for (pod in pods) {
                System.err.println(
                    "integration cleanup: sandbox $pod for server ${name.value} outlived the orchestrator and is " +
                        "being force-removed. Something did not tear down.",
                )
                runCatching { Crictl.forceRemovePod(pod) }
            }
        }
    }

    private suspend fun scrub(
        node: Node,
        name: ResourceName,
    ) {
        var observation = node.observe(name)
        val running = observation as? WorkloadObservation.Present
        if (running != null && running.state == WorkloadState.RUNNING) {
            runCatching { node.stopWorkload(running.handle, CLEANUP_GRACE) }
            repeat(CLEANUP_ATTEMPTS) {
                observation = node.observe(name)
                if ((observation as? WorkloadObservation.Present)?.state != WorkloadState.RUNNING) return@repeat
                delay(1.seconds)
            }
        }
        val present = node.observe(name) as? WorkloadObservation.Present ?: return
        runCatching { node.removeWorkload(present.handle) }
    }

    internal companion object {
        const val ENDPOINT_VARIABLE: String = "MCORCH_CRI_ENDPOINT"
        const val DEFAULT_ENDPOINT: String = "unix:///run/mcorch-dev/containerd.sock"
        const val SANDBOX_NAMESPACE: String = "mcorch-it"

        /**
         * A real Paper server took 43 seconds to become joinable on the runtime
         * this was written against; its container was `RUNNING` after two. That
         * gap is the entire reason readiness is a Server List Ping rather than a
         * container state, so the budget here is deliberately generous — and it
         * has to cover an image pull on a cold node too.
         */
        val READY_TIMEOUT: Duration = 5.minutes

        val DRAIN_TIMEOUT: Duration = 3.minutes

        private val POLL = 500.milliseconds
        private const val REPORT_EVERY_NANOS = 10_000_000_000L
        private val CLEANUP_GRACE = 20.seconds
        private const val CLEANUP_ATTEMPTS = 30

        /**
         * Every server name this suite uses.
         *
         * The sweep needs them because a test that failed before its definition
         * landed — or after it was purged — leaves a workload that
         * [Store.listServers] no longer mentions.
         */
        val PROBE_NAMES: List<ResourceName> =
            listOf("it-bringup", "it-noop", "it-drain", "it-replace", "it-nosave", "it-lobby")
                .map { ResourceName.of(it).getOrThrow() }

        fun endpoint(): String = System.getenv(ENDPOINT_VARIABLE)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT

        /**
         * Whether the dev containerd is actually there.
         *
         * Checked once, loudly, rather than as a per-test assumption: a suite
         * that skips itself when the runtime is missing reports green having
         * tested nothing, and this is the only place in the repo where the
         * untested assumptions get exercised at all.
         */
        fun requireContainerd() {
            val socket = endpoint().removePrefix("unix://")
            check(Files.exists(Path.of(socket))) {
                "no containerd socket at $socket. These tests run against this project's own containerd, not the " +
                    "host's: start it with scripts/dev/containerd-up.sh, or point $ENDPOINT_VARIABLE at another " +
                    "instance you are willing to have containers created in"
            }
        }

        private fun shared(path: Path): Path {
            path.createDirectories()
            runCatching {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxrwx"))
            }
            return path
        }
    }
}
