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
 * "Politely" is the only interesting part. A signal cancels the loop and waits
 * for the pass in flight to finish, because a pass killed mid-flight is a side
 * effect nobody recorded — a save request sent and forgotten, a container
 * created that the next process will not recognise. **Shutting down does not
 * stop anybody's server**: the containers keep running, and the next process to
 * start reconciles them back. Stopping a Minecraft server is a drain, and a
 * drain is never something that happens because an operator restarted the
 * orchestrator.
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
 * Installs a shutdown hook that cancels [loop] and waits for it.
 *
 * The wait is the point. Without it the JVM exits while a pass is part-way
 * through a container operation, and the record of what that pass did never
 * reaches the store.
 */
private fun stopOnSignal(loop: Job): Thread {
    val hook =
        Thread {
            LOG.info("stopping: waiting for the pass in flight to finish")
            loop.cancel(CancellationException("the orchestrator is shutting down"))
            runBlocking { loop.join() }
        }
    Runtime.getRuntime().addShutdownHook(hook)
    return hook
}

private const val EXIT_MISCONFIGURED = 78
