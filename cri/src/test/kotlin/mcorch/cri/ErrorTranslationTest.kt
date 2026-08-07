package mcorch.cri

import io.grpc.Status
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.seconds

/**
 * Error translation, driven end to end through the real wrapper and real gRPC
 * stubs against a fake CRI server.
 *
 * This is the contract `:core` codes against: a retryable failure requeues with
 * backoff, a permanent one surfaces on the server's observed status. Changing a
 * row here changes reconcile behaviour.
 */
class ErrorTranslationTest {
    data class Case(
        val code: Status.Code,
        val expected: Class<out CriException>,
        val retryable: Boolean,
        val expectedCode: CriStatusCode,
    ) {
        override fun toString(): String = "$code -> ${expected.simpleName} (retryable=$retryable)"
    }

    companion object {
        @JvmStatic
        fun cases(): List<Case> =
            listOf(
                // Retryable: nothing about the desired state is wrong, try later.
                Case(Status.Code.UNAVAILABLE, CriException.Unavailable::class.java, true, CriStatusCode.UNAVAILABLE),
                Case(
                    Status.Code.DEADLINE_EXCEEDED,
                    CriException.Timeout::class.java,
                    true,
                    CriStatusCode.DEADLINE_EXCEEDED,
                ),
                Case(
                    Status.Code.RESOURCE_EXHAUSTED,
                    CriException.ResourceExhausted::class.java,
                    true,
                    CriStatusCode.RESOURCE_EXHAUSTED,
                ),
                Case(Status.Code.ABORTED, CriException.Aborted::class.java, true, CriStatusCode.ABORTED),
                Case(Status.Code.UNKNOWN, CriException.RuntimeFailure::class.java, true, CriStatusCode.UNKNOWN),
                Case(Status.Code.INTERNAL, CriException.RuntimeFailure::class.java, true, CriStatusCode.INTERNAL),
                Case(Status.Code.DATA_LOSS, CriException.RuntimeFailure::class.java, true, CriStatusCode.DATA_LOSS),
                // Permanent: the request as written cannot succeed.
                Case(Status.Code.NOT_FOUND, CriException.NotFound::class.java, false, CriStatusCode.NOT_FOUND),
                Case(
                    Status.Code.ALREADY_EXISTS,
                    CriException.AlreadyExists::class.java,
                    false,
                    CriStatusCode.ALREADY_EXISTS,
                ),
                Case(
                    Status.Code.INVALID_ARGUMENT,
                    CriException.InvalidArgument::class.java,
                    false,
                    CriStatusCode.INVALID_ARGUMENT,
                ),
                Case(
                    Status.Code.OUT_OF_RANGE,
                    CriException.InvalidArgument::class.java,
                    false,
                    CriStatusCode.OUT_OF_RANGE,
                ),
                Case(
                    Status.Code.FAILED_PRECONDITION,
                    CriException.FailedPrecondition::class.java,
                    false,
                    CriStatusCode.FAILED_PRECONDITION,
                ),
                Case(
                    Status.Code.PERMISSION_DENIED,
                    CriException.PermissionDenied::class.java,
                    false,
                    CriStatusCode.PERMISSION_DENIED,
                ),
                Case(
                    Status.Code.UNAUTHENTICATED,
                    CriException.PermissionDenied::class.java,
                    false,
                    CriStatusCode.UNAUTHENTICATED,
                ),
                Case(
                    Status.Code.UNIMPLEMENTED,
                    CriException.Unimplemented::class.java,
                    false,
                    CriStatusCode.UNIMPLEMENTED,
                ),
                Case(Status.Code.CANCELLED, CriException.Cancelled::class.java, false, CriStatusCode.CANCELLED),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `runtime service failures translate to typed exceptions`(case: Case) =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(failWith = case.code.toStatus().withDescription("boom"))
            FakeCriServer(runtime = runtime).use { fake ->
                val thrown = shouldThrow<CriException> { fake.client.version() }

                thrown.shouldBeInstanceOf<CriException>()
                thrown::class.java shouldBe case.expected
                thrown.retryable shouldBe case.retryable
                thrown.code shouldBe case.expectedCode
                thrown.operation shouldBe CriOperation.VERSION
                thrown.message shouldContain "boom"
            }
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `image service failures translate to typed exceptions`(case: Case) =
        runCriTest {
            val images = FakeCriServer.ImageBehaviour(failWith = case.code.toStatus().withDescription("boom"))
            FakeCriServer(images = images).use { fake ->
                val thrown =
                    shouldThrow<CriException> { fake.client.pullImage(ImageName("itzg/minecraft-server:latest")) }

                thrown::class.java shouldBe case.expected
                thrown.retryable shouldBe case.retryable
                thrown.operation shouldBe CriOperation.PULL_IMAGE
            }
        }

    @Test
    fun `no grpc type escapes the wrapper`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(failWith = Status.NOT_FOUND)
            FakeCriServer(runtime = runtime).use { fake ->
                val thrown = shouldThrow<CriException> { fake.client.containerStatus(ContainerId("abc")) }

                // The gRPC exception is preserved as a cause for debugging, but
                // nothing downstream has to know it is there.
                thrown.shouldBeInstanceOf<CriException.NotFound>()
                (thrown.cause is io.grpc.StatusException || thrown.cause is io.grpc.StatusRuntimeException)
                    .shouldBeTrue()
            }
        }

    @Test
    fun `a pull failure is distinguishable from a create failure`() =
        runCriTest {
            val images = FakeCriServer.ImageBehaviour(failWith = Status.NOT_FOUND.withDescription("no such manifest"))
            val runtime = FakeCriServer.RuntimeBehaviour(failWith = Status.NOT_FOUND.withDescription("no such image"))
            FakeCriServer(runtime = runtime, images = images).use { fake ->
                val pull = shouldThrow<CriException> { fake.client.pullImage(ImageName("nope:1")) }
                val create =
                    shouldThrow<CriException> {
                        fake.client.createContainer(SandboxId("s"), sampleSandboxSpec(), sampleContainerSpec())
                    }

                pull.operation shouldBe CriOperation.PULL_IMAGE
                create.operation shouldBe CriOperation.CREATE_CONTAINER
                // Same code, same classification — only the operation tells them apart.
                pull.code shouldBe create.code
                pull.retryable.shouldBeFalse()
            }
        }

    @Test
    fun `the failing operation is reported on every call`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(failWith = Status.UNAVAILABLE)
            FakeCriServer(runtime = runtime).use { fake ->
                val client = fake.client
                shouldThrow<CriException> { client.status() }.operation shouldBe CriOperation.RUNTIME_STATUS
                shouldThrow<CriException> { client.runSandbox(sampleSandboxSpec()) }.operation shouldBe
                    CriOperation.RUN_SANDBOX
                shouldThrow<CriException> { client.stopSandbox(SandboxId("s")) }.operation shouldBe
                    CriOperation.STOP_SANDBOX
                shouldThrow<CriException> { client.removeSandbox(SandboxId("s")) }.operation shouldBe
                    CriOperation.REMOVE_SANDBOX
                shouldThrow<CriException> { client.sandboxStatus(SandboxId("s")) }.operation shouldBe
                    CriOperation.SANDBOX_STATUS
                shouldThrow<CriException> { client.listSandboxes() }.operation shouldBe CriOperation.LIST_SANDBOXES
                shouldThrow<CriException> { client.startContainer(ContainerId("c")) }.operation shouldBe
                    CriOperation.START_CONTAINER
                shouldThrow<CriException> {
                    client.stopContainer(ContainerId("c"), StopGracePeriod.ofSeconds(1).getOrThrow())
                }.operation shouldBe CriOperation.STOP_CONTAINER
                shouldThrow<CriException> { client.removeContainer(ContainerId("c")) }.operation shouldBe
                    CriOperation.REMOVE_CONTAINER
                shouldThrow<CriException> { client.listContainers() }.operation shouldBe CriOperation.LIST_CONTAINERS
                shouldThrow<CriException> {
                    client.execSync(ContainerId("c"), listOf("true"), 1.seconds)
                }.operation shouldBe CriOperation.EXEC_SYNC
                shouldThrow<CriException> {
                    client.execStreamUrl(ContainerId("c"), listOf("true"))
                }.operation shouldBe CriOperation.EXEC
            }
        }

    @ParameterizedTest
    @EnumSource(CriOperation::class)
    fun `every operation is nameable`(operation: CriOperation) {
        operation.name.isNotBlank().shouldBeTrue()
    }
}
