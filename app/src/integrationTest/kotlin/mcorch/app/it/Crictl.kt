package mcorch.app.it

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The runtime's own view, obtained without going through the code under test.
 *
 * Every other observation in this suite comes back through [mcorch.core.Node],
 * which reports *one* container per server by design — the one it adopted. That
 * is the right shape for the loop and the wrong shape for asking "did this
 * change ever run two containers at once", which is what `failure-modes.md`
 * item 5 is about. A test that asked the loop whether the loop had made a second
 * container would agree with itself.
 *
 * So this shells out to `crictl` against the same endpoint. It is only ever used
 * to *look*; the tests do their removing through the Node, so that a workload
 * the loop failed to clean up shows up as a failure rather than being quietly
 * tidied away.
 */
internal object Crictl {
    /** Container ids carrying this orchestrator's label for [server], in any state. */
    fun containers(server: String): List<String> = run("ps", "-a", "-q", "--label", "mcorch.dev/server=$server")

    /** Sandbox ids carrying this orchestrator's label for [server], in any state. */
    fun sandboxes(server: String): List<String> = run("pods", "-q", "--label", "mcorch.dev/server=$server")

    /**
     * Force-removes a sandbox and everything in it.
     *
     * **Test cleanup only, and deliberately not reachable from anything the
     * orchestrator does.** This is the `crictl rmp -f` an operator should never
     * need; it exists so that a test which fails part-way through a drain does
     * not leave a container behind to poison the next run. Callers announce what
     * they are doing this to, because anything reaching here is a leak worth
     * reading about.
     */
    fun forceRemovePod(id: String): Unit = run("rmp", "-f", id).let { }

    private fun run(vararg arguments: String): List<String> {
        val command = listOf(binary(), "--runtime-endpoint", ContainerdHarness.endpoint()) + arguments
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        check(finished) {
            process.destroyForcibly()
            "`${command.joinToString(" ")}` did not finish within ${TIMEOUT_SECONDS}s"
        }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        check(process.exitValue() == 0) {
            "`${command.joinToString(" ")}` exited ${process.exitValue()}: ${err.trim()}"
        }
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun binary(): String =
        CANDIDATES.firstOrNull { Files.isExecutable(Path.of(it)) }
            ?: error(
                "crictl is not installed. It comes with scripts/dev/containerd-up.sh, and these tests use it to " +
                    "observe the runtime independently of the code they are testing",
            )

    private val CANDIDATES = listOf("/usr/local/bin/crictl", "/usr/bin/crictl")
    private const val TIMEOUT_SECONDS = 30L
}
