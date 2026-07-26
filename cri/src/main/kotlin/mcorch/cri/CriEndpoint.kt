package mcorch.cri

/**
 * Where the CRI service is listening.
 *
 * Nothing in this module assumes "the local one". A [CriClient] is constructed
 * against an explicit endpoint, which is what lets a future remote `Node`
 * implementation address a different host without changing this module.
 */
public sealed interface CriEndpoint {
    /** Human-readable form, suitable for logs and error messages. */
    public val description: String

    /** A Unix domain socket, which is how containerd serves CRI on a single host. */
    public data class UnixSocket(
        val path: String,
    ) : CriEndpoint {
        init {
            require(path.isNotBlank()) { "unix socket path must not be blank" }
            require(path.startsWith("/")) { "unix socket path must be absolute, got: $path" }
        }

        override val description: String get() = "unix://$path"
    }

    /**
     * A TCP endpoint. containerd does not serve CRI over TCP by default; this
     * exists so the endpoint type does not have to change shape when a remote
     * node arrives, and so tests can point at a stand-in.
     */
    public data class Tcp(
        val host: String,
        val port: Int,
    ) : CriEndpoint {
        init {
            require(host.isNotBlank()) { "host must not be blank" }
            require(port in 1..65535) { "port must be in 1..65535, got: $port" }
        }

        override val description: String get() = "tcp://$host:$port"
    }

    public companion object {
        /**
         * The environment variable `crictl` and this repo's dev scripts use.
         * `scripts/dev/containerd-up.sh` prints the value to export.
         */
        public const val ENDPOINT_ENV_VAR: String = "CONTAINER_RUNTIME_ENDPOINT"

        /**
         * The socket `scripts/dev/containerd-up.sh` creates. That instance is
         * deliberately separate from any system containerd, so this is a dev
         * convenience only — production wiring passes an endpoint explicitly.
         */
        public const val DEV_SOCKET_PATH: String = "/run/mcorch-dev/containerd.sock"

        /** The dev containerd from `scripts/dev/containerd-up.sh`. */
        public fun devDefault(): CriEndpoint = UnixSocket(DEV_SOCKET_PATH)

        /**
         * Parses the endpoint forms containerd tooling accepts:
         * `unix:///run/x.sock`, `unix:/run/x.sock`, `tcp://host:port`, and a
         * bare absolute path (treated as a Unix socket).
         *
         * @throws IllegalArgumentException if [endpoint] is not one of those.
         */
        public fun parse(endpoint: String): CriEndpoint {
            val trimmed = endpoint.trim()
            require(trimmed.isNotEmpty()) { "endpoint must not be blank" }
            return when {
                trimmed.startsWith("unix://") -> UnixSocket(trimmed.removePrefix("unix://"))

                trimmed.startsWith("unix:") -> UnixSocket(trimmed.removePrefix("unix:"))

                trimmed.startsWith("tcp://") -> parseTcp(trimmed.removePrefix("tcp://"))

                trimmed.startsWith("/") -> UnixSocket(trimmed)

                else -> throw IllegalArgumentException(
                    "unrecognised CRI endpoint '$endpoint'; expected unix:///path, tcp://host:port, or an absolute path",
                )
            }
        }

        /**
         * Reads [ENDPOINT_ENV_VAR], or returns `null` when it is unset or blank.
         *
         * Deliberately not defaulted: silently falling back to the dev socket
         * would let a misconfigured deployment talk to the wrong containerd.
         * Callers decide what "unset" means.
         */
        public fun fromEnvironment(getenv: (String) -> String? = System::getenv): CriEndpoint? =
            getenv(ENDPOINT_ENV_VAR)?.takeIf { it.isNotBlank() }?.let(::parse)

        private fun parseTcp(hostPort: String): Tcp {
            val separator = hostPort.lastIndexOf(':')
            require(separator > 0) { "tcp endpoint must be host:port, got: tcp://$hostPort" }
            val port = hostPort.substring(separator + 1).toIntOrNull()
            requireNotNull(port) { "tcp endpoint port is not a number: tcp://$hostPort" }
            return Tcp(hostPort.substring(0, separator), port)
        }
    }
}
