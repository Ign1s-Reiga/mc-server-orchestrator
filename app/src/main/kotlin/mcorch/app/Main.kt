package mcorch.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("mcorch.app.Main")

/**
 * Entry point: build the composition root, run the reconcile loop, stop
 * politely.
 *
 * **Shutting down does not stop anybody's server**: the containers keep
 * running, and the next process to start reconciles them back. Stopping a
 * Minecraft server is a drain, and a drain is never something that happens
 * because an operator restarted the orchestrator.
 *
 * ## What "politely" does and does not mean
 *
 * A signal cancels the loop and waits for it to *unwind* — every worker, the
 * resync ticker, the change-feed poller and every pending requeue — before the
 * node and the store are closed. That ordering is what it buys: no pass is
 * part-way through a store write when the database handle goes away, and every
 * server's name is released back to the queue.
 *
 * It does **not** wait for a pass in flight to finish, and this comment used to
 * claim it did. Cancellation reaches a running pass at its next suspension
 * point and the pass unwinds from there. That is deliberate — waiting would
 * mean waiting out a save timeout, minutes long, on every restart — but it
 * leaves one narrow window worth knowing about: a pass cancelled between
 * issuing a side effect and recording it loses the record, and the only side
 * effect that cannot be re-derived by observing the runtime is a save request
 * (`DrainStatus.saveRequestedAt`). Closing that window means making the write
 * that follows an issued side effect non-cancellable, which is drain-protocol
 * territory and has not been done.
 */
public fun main() {
    val config =
        try {
            OrchestratorConfig.fromEnvironment(System.getenv())
        } catch (invalid: IllegalArgumentException) {
            LOG.error("cannot start: {}", invalid.message)
            exitProcess(EXIT_MISCONFIGURED)
        }

    Orchestrator.open(config).use { orchestrator ->
        runBlocking {
            val loop = launch { orchestrator.run() }
            val stopping = stopOnSignal(loop)
            try {
                loop.join()
            } finally {
                // Removing a hook throws once shutdown has begun, which is
                // exactly the case where it does not matter.
                runCatching { Runtime.getRuntime().removeShutdownHook(stopping) }
            }
        }
    }
    LOG.info("orchestrator stopped; the servers it manages are still running")
}

/**
 * Installs a shutdown hook that cancels [loop] and waits for it to unwind.
 *
 * The wait is the point. Without it the JVM exits while the loop is still
 * unwinding, and `Orchestrator.close` takes the store and the gRPC channel away
 * underneath it. What the wait does not do is let an in-flight pass complete —
 * see the note on [main].
 */
private fun stopOnSignal(loop: Job): Thread {
    val hook =
        Thread {
            LOG.info("stopping: cancelling the reconcile loop and waiting for it to unwind")
            loop.cancel(CancellationException("the orchestrator is shutting down"))
            runBlocking { loop.join() }
        }
    Runtime.getRuntime().addShutdownHook(hook)
    return hook
}

private const val EXIT_MISCONFIGURED = 78
