package mcorch.core

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
 * A failure's detail reaches disk and the API, so only the redacted form may
 * travel — all the way down, not just as far as the object the reconciler built.
 *
 * ## What this is for, now that `:cri` makes the decision
 *
 * The withholding decision itself belongs to `CriException.safeDescription` in
 * `:cri` and is tested there, against a list this module deliberately no longer
 * consults. This test covers the part `:cri` cannot see: **what happens to the
 * exception after `LocalNode` has translated it.**
 *
 * That is not a formality. A [NodeException] keeps the original exception as its
 * `cause`, and that object still holds the runtime's *unredacted* description —
 * by design, so a stack trace inside `:cri` says everything.
 * `safeMessage` therefore protects the message and nothing else. Anything
 * downstream that serialised the cause chain instead of the message — a status
 * encoder that recorded the whole throwable, a change-log row that kept the
 * pre-translation value — would put the runtime's raw text into `state.db`
 * while every `:cri` test still passed.
 *
 * So the assertions are against the **bytes of the database file** after a real
 * round trip through the real store, with secret-shaped material reachable only
 * through the cause. The control assertion is not decoration: it has already
 * caught a version of this test in which the needle was never findable, and
 * without it "the secret is not in the file" passes for the wrong reason.
 */
internal class FailureDetailPersistenceTest {
    private val directories = mutableListOf<Path>()

    @AfterEach
    fun cleanUp() {
        directories.forEach { it.toFile().deleteRecursively() }
        directories.clear()
    }

    private fun directory(): Path = Files.createTempDirectory("mcorch-failure-detail").also { directories.add(it) }

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
    fun `the unredacted text a failure still carries as its cause never reaches the database`() =
        coreTest {
            val directory = directory()
            val secret = material()
            val diagnostic = "timeout-" + UUID.randomUUID().toString().replace("-", "")

            // Stands in for the `CriException` a `NodeException` keeps: its
            // description is the runtime's own, quoting the request it rejected.
            // `LocalNode` passes `safeMessage` as the message and this as the
            // cause, so this object is genuinely on the path.
            fun rawCause() =
                RuntimeException(
                    "invalid container config: &ContainerConfig{Envs:[]&KeyValue{Key:RCON_PASSWORD,Value:$secret,}}",
                )

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory)).use { embedded ->
                val clock = MutableClock()
                val node = FakeNode(clock = clock)
                val registry = StaticNodeRegistry(listOf(node))
                val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry), clock = clock)

                // The control's server first: brought up to running, then its
                // probe fails. EXEC is never withheld — no call site puts a
                // credential in an argv — so its message carries the diagnostic
                // whole, and that is what proves the byte search works.
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
                        "EXEC_SYNC failed (DEADLINE_EXCEEDED, retryable): $diagnostic exceeded",
                        rawCause(),
                    ),
                )
                reconciler.reconcile(healthy.metadata.name)

                // Then a create rejected by the runtime. The message is the
                // redacted form `LocalNode` would hand on; the unredacted text
                // exists only on the cause, which is exactly the arrangement in
                // production.
                node.stopFailing(NodeOperation.EXEC)
                node.workload = WorkloadObservation.Absent
                node.failAlways(
                    NodeOperation.CREATE,
                    NodeException.Rejected(
                        node.name,
                        NodeOperation.CREATE,
                        "CREATE_CONTAINER failed (INVALID_ARGUMENT, permanent): <withheld>",
                        rawCause(),
                    ),
                )
                val rejected = paperDefinition(name = "leaky-01")
                embedded.state.putDefinition(rejected).getOrThrow()
                reconciler.reconcile(rejected.metadata.name)

                // Both failures really were recorded, so the file assertions
                // below are about a database with something in it.
                val recorded =
                    (
                        embedded.state
                            .getServer(rejected.metadata.name)
                            .shouldNotBeNull()
                            .status
                            ?.status as? PaperServerStatus
                    )?.failure.shouldNotBeNull()
                recorded.message shouldNotContain secret
                val kept =
                    (
                        embedded.state
                            .getServer(healthy.metadata.name)
                            .shouldNotBeNull()
                            .status
                            ?.status as? PaperServerStatus
                    )?.failure.shouldNotBeNull()
                kept.message shouldContain diagnostic
            }

            // Closed, so everything is flushed out of the WAL.
            val files = stateBytes(directory)
            files.isEmpty() shouldBe false
            for (bytes in files) {
                contains(bytes, secret.toByteArray(Charsets.UTF_8)) shouldBe false
            }
            // Control: the same search over the same files finds the detail that
            // is supposed to be there, so the assertion above failing to find
            // the secret means something.
            files.any { contains(it, diagnostic.toByteArray(Charsets.UTF_8)) } shouldBe true
        }
}
