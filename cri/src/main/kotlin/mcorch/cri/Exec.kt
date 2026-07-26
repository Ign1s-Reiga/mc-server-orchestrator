package mcorch.cri

/**
 * The result of a synchronous exec.
 *
 * [stdout] and [stderr] are decoded as UTF-8. CRI caps each at 16 MiB and
 * silently discards the rest, so a command that produces more than that is
 * reported as succeeding with truncated output — do not use this to stream logs.
 */
public data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    /** Whether the command exited zero. Says nothing about whether it did what you wanted. */
    public val succeeded: Boolean get() = exitCode == 0
}

/**
 * Which streams an exec session attaches.
 *
 * CRI requires at least one of stdin/stdout/stderr, and forbids stderr together
 * with a TTY, because a TTY multiplexes both into one stream.
 */
public data class ExecStreams(
    val stdin: Boolean = false,
    val stdout: Boolean = true,
    val stderr: Boolean = true,
    val tty: Boolean = false,
) {
    init {
        require(stdin || stdout || stderr) { "exec must attach at least one of stdin, stdout, stderr" }
        require(!(tty && stderr)) {
            "CRI forbids stderr with tty: a TTY combines both into stdout. Set stderr = false."
        }
    }

    public companion object {
        /** Capture output, send nothing. */
        public val OUTPUT_ONLY: ExecStreams = ExecStreams()

        /** An interactive console: stdin plus a combined output stream. */
        public val INTERACTIVE_TTY: ExecStreams =
            ExecStreams(stdin = true, stdout = true, stderr = false, tty = true)
    }
}
