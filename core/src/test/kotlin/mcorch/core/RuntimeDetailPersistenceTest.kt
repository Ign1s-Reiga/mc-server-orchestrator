package mcorch.core

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mcorch.core.node.runtimeDetail
import mcorch.schema.PaperServerStatus
import mcorch.store.getOrThrow
import mcorch.store.sqlite.EmbeddedStore
import mcorch.store.sqlite.EmbeddedStoreConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A runtime's account of a failure ends up on disk and on the wire, so the
 * redaction has to hold all the way through — not just in the object the
 * reconciler built.
 *
 * `FailureStatus.message` is written into `state.db` and served through the API.
 * That is a materially worse exposure than a log line: a log is a one-shot write
 * into a stream an operator controls and can rotate, this is data at rest and in
 * an HTTP response. So these assertions are against the **bytes of the database
 * file**, after a real round trip through the real store, rather than against
 * the status object — an encoder that stored the original alongside the redacted
 * copy would satisfy the weaker check.
 *
 * Modelled on `:store`'s `SecretLeakageTest`, including its control assertion:
 * without one, "the secret is not in the file" passes just as well when the
 * needle was never findable in the first place.
 */
internal class RuntimeDetailPersistenceTest {
    private val directories = mutableListOf<Path>()

    @AfterEach
    fun cleanUp() {
        directories.forEach { it.toFile().deleteRecursively() }
        directories.clear()
    }

    private fun directory(): Path = Files.createTempDirectory("mcorch-runtime-detail").also { directories.add(it) }

    /** Generated, never a literal: a literal is how a real one ends up committed. */
    private fun material(): String = "s3cr3t-" + UUID.randomUUID().toString().replace("-", "")

    private fun stateBytes(directory: Path): List<ByteArray> =
        directory
            .toFile()
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("state.db") }
            .map { it.readBytes() }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    @Test
    fun `a create failure that quotes its own request never reaches the database`() =
        coreTest {
            val directory = directory()
            val secret = material()
            // The token the control looks for: it rides in on an EXEC failure,
            // which is deliberately not redacted, and proves the byte search
            // works and that the useful diagnostic really is being kept.
            val diagnostic = "timeout-" + UUID.randomUUID().toString().replace("-", "")

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory)).use { embedded ->
                val clock = MutableClock()
                val node = FakeNode(clock = clock)
                val registry = StaticNodeRegistry(listOf(node))
                val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry), clock = clock)

                // First, the control's server: brought up to running, then its
                // *probe* fails. EXEC carries no credential in its argv, so its
                // description is deliberately kept whole — which is what makes
                // it usable as proof that the byte search below works at all.
                //
                // It runs first because `FakeNode` holds a single workload, so
                // the create failure staged afterwards would otherwise stop this
                // server ever reaching a probe.
                val healthy = paperDefinition(name = "chatty-01")
                embedded.state.putDefinition(healthy).getOrThrow()
                repeat(2) { reconciler.reconcile(healthy.metadata.name) }
                node.failAlways(
                    NodeOperation.EXEC,
                    NodeException.Timeout(
                        node.name,
                        NodeOperation.EXEC,
                        runtimeDetail(
                            operation = "EXEC_SYNC",
                            code = "DEADLINE_EXCEEDED",
                            rendered = "EXEC_SYNC failed (DEADLINE_EXCEEDED, retryable): $diagnostic exceeded",
                            requestMayCarrySecrets = false,
                        ),
                    ),
                )
                reconciler.reconcile(healthy.metadata.name)
                val kept =
                    (
                        embedded.state
                            .getServer(healthy.metadata.name)
                            .shouldNotBeNull()
                            .status
                            ?.status as? PaperServerStatus
                    )?.failure.shouldNotBeNull()
                kept.message shouldContain diagnostic

                // Now a server whose container create is rejected by a runtime
                // that rendered the request it rejected — the container
                // environment, RCON password and all — into its error string.
                node.stopFailing(NodeOperation.EXEC)
                node.workload = WorkloadObservation.Absent
                node.failAlways(
                    NodeOperation.CREATE,
                    NodeException.Rejected(
                        node.name,
                        NodeOperation.CREATE,
                        runtimeDetail(
                            operation = "CREATE_CONTAINER",
                            code = "INVALID_ARGUMENT",
                            rendered =
                                "CREATE_CONTAINER failed (INVALID_ARGUMENT, permanent): invalid config: " +
                                    "&ContainerConfig{Envs:[]&KeyValue{Key:RCON_PASSWORD,Value:$secret,}}",
                            requestMayCarrySecrets = true,
                        ),
                    ),
                )
                val rejected = paperDefinition(name = "leaky-01")
                embedded.state.putDefinition(rejected).getOrThrow()
                reconciler.reconcile(rejected.metadata.name)

                // Both failures really were recorded, so the file assertions
                // below are about a database that has something in it.
                val stored = embedded.state.getServer(rejected.metadata.name).shouldNotBeNull()
                val failure = (stored.status?.status as? PaperServerStatus)?.failure.shouldNotBeNull()
                failure.message shouldNotContain secret
                failure.message shouldContain "CREATE_CONTAINER"
            }

            // The store is closed, so everything is flushed to disk.
            val files = stateBytes(directory)
            files.isEmpty() shouldBe false
            for (bytes in files) {
                contains(bytes, secret.toByteArray(Charsets.UTF_8)) shouldBe false
            }
            // Control. The same search, over the same files, finds the detail
            // that is *supposed* to be there — so the assertion above failing to
            // find the secret means something.
            files.any { contains(it, diagnostic.toByteArray(Charsets.UTF_8)) } shouldBe true
        }
}
