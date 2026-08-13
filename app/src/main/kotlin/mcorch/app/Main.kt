package mcorch.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mcorch.api.ApiConfig
import mcorch.api.ApiConfiguration
import mcorch.api.ApiServer
import org.slf4j.LoggerFactory
import java.io.IOException
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
 * mean waiting out a save timeout, minutes long, on every restart.
 *
 * What it does wait for is the *record* of anything that pass has already done.
 * A pass cancelled between issuing a side effect and recording it used to lose
 * the record, and the only side effect that cannot be re-derived by observing
 * the runtime is a save request — so the next process would send a second one.
 * `Reconciler.recordIssuedSideEffect` now writes that record under
 * `NonCancellable`, which bounds a shutdown by one store write rather than by a
 * container operation. Both halves of the record are covered:
 * `DrainStatus.saveRequestedAt` for a save that was delivered and never
 * confirmed, `DrainStatus.worldSavedAt` for one the server reported completed.
 *
 * ## The ordering below is load-bearing, not stylistic
 *
 * `loop.join()` runs inside `use`, so the store is closed **after** the loop has
 * finished unwinding. That is what makes the shield above worth anything: an
 * uncancellable write into a store that has already been closed throws, and the
 * record is lost exactly as it was before. Two things hold it up, and both are
 * pinned by tests rather than by this comment —
 * `ReconcileLoopTest` asserts the loop does not finish unwinding until an issued
 * save is recorded, and `Orchestrator.close` reports it at error if it is called
 * while the loop is still running. Anything that moves the close earlier, or
 * adds a second shutdown hook that closes the store, breaks it.
 *
 * The dashboard API nests inside the same ordering and for the same reason: it
 * is started after the store is open and closed before it, so no request can be
 * part-way through a store call when the handle goes away. It is *not* wired
 * into [Orchestrator], which the integration suite drives directly — a suite
 * that had to invent an operator credential to reconcile a container would be
 * paying for a dependency it does not have. See [serveApi].
 */
public fun main() {
    val config =
        try {
            OrchestratorConfig.fromEnvironment(System.getenv())
        } catch (invalid: IllegalArgumentException) {
            LOG.error("cannot start: {}", invalid.message)
            exitProcess(EXIT_MISCONFIGURED)
        }

    val apiConfiguration =
        try {
            ApiConfig.fromEnvironment(System.getenv())
        } catch (invalid: IllegalArgumentException) {
            LOG.error("cannot start: {}", invalid.message)
            exitProcess(EXIT_MISCONFIGURED)
        }

    // Inside the misconfiguration channel, and not only for symmetry. `open` wires
    // the node, and `LocalNode.open`'s stop-deadline pre-flight throws
    // `IllegalArgumentException` with a message naming which constant to move.
    // Outside this catch that message reached an operator as an uncaught stack trace
    // on the default exit code — the one presentation guaranteed to be read as a
    // crash rather than as a thing to go and fix. Nothing has been reconciled at this
    // point, so the exit is as clean as the two config reads above.
    val orchestrator =
        try {
            Orchestrator.open(config)
        } catch (invalid: IllegalArgumentException) {
            LOG.error("cannot start: {}", invalid.message)
            exitProcess(EXIT_MISCONFIGURED)
        }

    orchestrator.use { orchestrator ->
        // The API is opened *inside* the orchestrator's `use` and closed before
        // it, so no request can be in a store call when the store is closed. It
        // is deliberately not part of `Orchestrator.open`: the composition root
        // is what the integration suite drives, and a suite that had to invent an
        // operator credential to reconcile a container would be paying for a
        // dependency it does not have.
        serveApi(apiConfiguration, orchestrator).use {
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
    }
    LOG.info("orchestrator stopped; the servers it manages are still running")
}

/**
 * Starts the dashboard backend, or explains that it is off.
 *
 * There are two states and no third: a configured API with an operator
 * credential, or `MCORCH_API_LISTEN=off`. An API that came up unauthenticated
 * because a variable was unset is not one of them — every mutating endpoint can
 * request a drain, and a drain is how a Minecraft server stops.
 *
 * Failing to bind is fatal rather than degraded. An orchestrator whose dashboard
 * silently did not start looks exactly like a healthy one until somebody needs it.
 */
private fun serveApi(
    configuration: ApiConfiguration,
    orchestrator: Orchestrator,
): AutoCloseable =
    when (configuration) {
        ApiConfiguration.Disabled -> {
            LOG.info(
                "the dashboard API is off ({}={}); this process reconciles but serves nothing",
                ApiConfig.LISTEN_VARIABLE,
                ApiConfig.DISABLED,
            )
            AutoCloseable {}
        }

        is ApiConfiguration.Listening -> {
            try {
                ApiServer.start(
                    configuration.config,
                    orchestrator.store,
                    orchestrator.secrets,
                    orchestrator.identities,
                    orchestrator.console,
                    orchestrator.forcedTermination,
                )
            } catch (failure: IOException) {
                LOG.error(
                    "cannot start: the dashboard API could not bind {}:{} — {}",
                    configuration.config.bindHost,
                    configuration.config.bindPort,
                    failure.message,
                )
                exitProcess(EXIT_MISCONFIGURED)
            }
        }
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
