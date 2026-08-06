package mcorch.cri.it

import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.netty.channel.ChannelOption
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDomainSocketChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.unix.DomainSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mcorch.cri.ContainerState
import mcorch.cri.StopGracePeriod
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import runtime.v1.RuntimeServiceGrpcKt
import runtime.v1.stopContainerRequest
import java.util.concurrent.TimeUnit

/**
 * Where containerd stops honouring a stop grace period, measured rather than
 * assumed.
 *
 * [StopGracePeriod.MAX_SECONDS] is a number in this repo's source, and the only
 * thing that makes it the *right* number is the container runtime on the other
 * side of the socket. CRI sends the grace period as `int64` seconds; containerd
 * multiplies by a billion into a Go `time.Duration`, which is `int64`
 * nanoseconds; above `(2^63 - 1) / 1e9` that product wraps. The wrap is silent
 * in both directions — `StopContainerResponse` has no fields at all, so the `{}`
 * that comes back from a stop which waited three centuries and the `{}` from one
 * that killed the container in 300 ms are the same message.
 *
 * These two tests are the boundary's two sides. **If the second one ever fails,
 * containerd has changed its arithmetic** — which is a good thing, and the
 * guard should be re-derived against the new runtime rather than the test
 * relaxed.
 *
 * Not wired into `check`: this needs `scripts/dev/containerd-up.sh` first.
 */
internal class StopGracePeriodBoundaryIT {
    @Test
    fun `the largest grace period the guard allows is one containerd actually serves`() {
        runBlocking {
            RuntimeHarness().use { harness ->
                val container = harness.startSigtermIgnoringContainer()
                val grace = StopGracePeriod.ofSeconds(StopGracePeriod.MAX_SECONDS).getOrThrow()

                // Cancelled from the outside rather than waited out — the grace
                // period under test is about 292 years. That it *can* be
                // cancelled is half the claim: a stop with a long grace period
                // must not be a call the reconcile loop cannot get out of.
                val finished =
                    withTimeoutOrNull(RuntimeHarness.WATCH) {
                        harness.client.stopContainer(container, grace)
                        true
                    }

                withClue("containerd returned from a ${grace.seconds}s stop within ${RuntimeHarness.WATCH}") {
                    finished shouldBe null
                }
                // Still waiting, not quietly finished: the container ignored
                // SIGTERM and containerd had not reached the kill.
                harness.state(container) shouldBe ContainerState.RUNNING
                println(
                    "MAX_SECONDS=${grace.seconds}: still waiting after ${RuntimeHarness.WATCH}, container RUNNING",
                )
            }
        }
    }

    @Test
    fun `one second past the guard, containerd kills at once and still reports success`() {
        runBlocking {
            RuntimeHarness().use { harness ->
                val container = harness.startSigtermIgnoringContainer()
                // Deliberately built on the raw stub. [StopGracePeriod] exists to
                // make this value unsendable, so the only way to keep measuring
                // what it protects against is to go under it. This characterizes
                // containerd; it is not a use of the wrapper.
                val overflowing = StopGracePeriod.MAX_SECONDS + 1

                val startedAt = System.nanoTime()
                rawChannel().use { raw ->
                    RuntimeServiceGrpcKt
                        .RuntimeServiceCoroutineStub(raw.channel)
                        .withDeadlineAfter(RuntimeHarness.WATCH.inWholeSeconds, TimeUnit.SECONDS)
                        .stopContainer(
                            stopContainerRequest {
                                containerId = container.value
                                timeout = overflowing
                            },
                        )
                }
                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                val state = harness.state(container)

                println(
                    "MAX_SECONDS+1=$overflowing: containerd returned success after " +
                        "${"%.2f".format(elapsedSeconds)}s and left the container $state",
                )
                // The RPC succeeded — no error, no warning, nothing a caller
                // could branch on — and the container is gone. Asking for 292
                // years of grace got none of it.
                withClue("containerd honoured $overflowing seconds; MAX_SECONDS is no longer the boundary") {
                    state shouldBe ContainerState.EXITED
                }
                withClue("the kill took ${elapsedSeconds}s, so it was not the immediate one the wrap produces") {
                    (elapsedSeconds < RuntimeHarness.WATCH.inWholeSeconds) shouldBe true
                }
            }
        }
    }

    /**
     * A channel of this suite's own, because the wrapper deliberately offers no
     * way to send the value under test.
     */
    private fun rawChannel(): RawChannel {
        val path = RuntimeHarness.endpoint().removePrefix("unix://")
        check(Epoll.isAvailable()) { "these tests need netty's native epoll for the Unix socket endpoint" }
        val group = EpollEventLoopGroup()
        return RawChannel(
            NettyChannelBuilder
                .forAddress(DomainSocketAddress(path))
                .eventLoopGroup(group)
                .channelType(EpollDomainSocketChannel::class.java)
                // A domain socket has no SO_KEEPALIVE, and grpc-netty sets it on
                // every client bootstrap. Removed rather than set, for the same
                // reason `mcorch.cri.internal.buildChannel` removes it: the
                // warning per channel is noise that says nothing.
                .withOption(ChannelOption.SO_KEEPALIVE, null)
                .usePlaintext()
                .build(),
            group,
        )
    }

    private class RawChannel(
        val channel: ManagedChannel,
        private val group: EpollEventLoopGroup,
    ) : AutoCloseable {
        override fun close() {
            channel.shutdownNow()
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS)
        }
    }

    internal companion object {
        @JvmStatic
        @BeforeAll
        fun requireRuntime() {
            RuntimeHarness.requireContainerd()
        }
    }
}
