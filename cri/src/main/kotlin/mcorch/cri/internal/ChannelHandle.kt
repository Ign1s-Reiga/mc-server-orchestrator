package mcorch.cri.internal

import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDomainSocketChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.unix.DomainSocketAddress
import mcorch.cri.CriClientConfig
import mcorch.cri.CriEndpoint

/** A channel plus the event loop group this module owns and must shut down, if any. */
internal class ChannelHandle(
    val channel: ManagedChannel,
    val eventLoopGroup: EventLoopGroup?,
)

/**
 * Builds the gRPC channel for a [CriEndpoint].
 *
 * Unix domain sockets need the transport wired by hand. grpc-java's
 * `io.grpc.netty.Utils` picks `EpollSocketChannel` when epoll is available, but
 * it never selects `EpollDomainSocketChannel` on its own and has no
 * `DomainSocketAddress` detection, so both the channel type and a matching
 * epoll event loop group have to be set explicitly. Supplying the group also
 * means owning it: [ChannelHandle.eventLoopGroup] is shut down alongside the
 * channel.
 */
internal fun buildChannel(config: CriClientConfig): ChannelHandle =
    when (val endpoint = config.endpoint) {
        is CriEndpoint.UnixSocket -> {
            check(Epoll.isAvailable()) {
                "the CRI endpoint ${endpoint.description} is a Unix domain socket, but netty's native epoll " +
                    "transport is not available on this platform, so no connection can be made. " +
                    "Cause: ${Epoll.unavailabilityCause()?.message ?: "unknown"}"
            }
            val group = EpollEventLoopGroup()
            val channel =
                NettyChannelBuilder
                    .forAddress(DomainSocketAddress(endpoint.path))
                    .eventLoopGroup(group)
                    .channelType(EpollDomainSocketChannel::class.java)
                    .maxInboundMessageSize(config.maxInboundMessageSizeBytes)
                    // grpc-netty sets SO_KEEPALIVE on every client bootstrap it
                    // builds, on the assumption that a channel ignoring an
                    // unsupported option is harmless. A domain socket has no such
                    // option, so netty warns about it once per channel — noise
                    // that says nothing and trains an operator to skim netty
                    // warnings. Passing null *removes* the option rather than
                    // setting it (netty's `AbstractBootstrap.option` contract),
                    // and user options are applied after grpc's own, so this
                    // wins. Deliberately only on this branch: on TCP below,
                    // SO_KEEPALIVE is a real setting that is wanted.
                    .withOption(ChannelOption.SO_KEEPALIVE, null)
                    // A Unix socket is already a local, authenticated channel;
                    // there is no TLS to negotiate over it.
                    .usePlaintext()
                    .build()
            ChannelHandle(channel, group)
        }

        is CriEndpoint.Tcp -> {
            ChannelHandle(
                NettyChannelBuilder
                    .forAddress(endpoint.host, endpoint.port)
                    .maxInboundMessageSize(config.maxInboundMessageSizeBytes)
                    .usePlaintext()
                    .build(),
                null,
            )
        }
    }
