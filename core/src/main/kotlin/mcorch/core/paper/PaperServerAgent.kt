package mcorch.core.paper

import mcorch.core.ExecOutcome
import mcorch.core.ExecRequest
import mcorch.core.Node
import mcorch.core.NodeException
import mcorch.core.WorkloadHandle
import mcorch.schema.PaperServerDefinition
import mcorch.schema.RconSpec
import mcorch.schema.StorageSpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the loop talks to a running Paper server.
 *
 * Two questions only, and both are asked by running a command inside the
 * container:
 *
 * 1. **Is it joinable, and how many players are on it?** Answered by a Server
 *    List Ping, which is the same handshake a real client makes. A container in
 *    state `RUNNING` says nothing about this: a Paper server is `RUNNING` for
 *    the whole of world generation and stays `RUNNING` while deadlocked.
 * 2. **Did the world save complete?** Answered by an RCON `save-all flush` and
 *    by *reading the server's reply*. The request going out is not the save
 *    finishing (`failure-modes.md` item 2), and a command that exits zero
 *    having printed an error is a failed save.
 *
 * **The command lines and the reply patterns below are unverified against a
 * real image.** They are written against `itzg/minecraft-server`, which ships
 * `mc-monitor` and `rcon-cli`. Everything image-specific is in this one file so
 * an integration test can correct it in one place.
 */
internal class PaperServerAgent(
    private val definition: PaperServerDefinition,
) {
    private val spec get() = definition.spec

    /**
     * Whether this server holds world data that must be saved before it stops.
     *
     * False only for `ephemeral` storage, which the operator has to ask for by
     * name and which by definition has nothing worth flushing.
     */
    val savePersistsWorld: Boolean get() = spec.storage is StorageSpec.Persistent

    /**
     * Whether there is any channel through which a save can be *confirmed*.
     *
     * Confirmation needs a reply, and the only thing that replies is RCON.
     * Without it the loop can push a save request into the console and learn
     * nothing about whether it finished — which is precisely the conflation the
     * drain protocol forbids. A server with world data and no RCON therefore
     * cannot be drained, and the loop says so rather than stopping it anyway.
     */
    val saveConfirmable: Boolean get() = spec.network.rcon is RconSpec.Enabled

    /** Asks the server whether it is accepting players, and how many it has. */
    suspend fun probe(
        node: Node,
        handle: WorkloadHandle,
    ): ProbeOutcome {
        val request =
            ExecRequest(
                command = PaperCommands.serverListPing(spec.network.port),
                timeout = PROBE_TIMEOUT,
            )
        val result =
            try {
                node.exec(handle, request)
            } catch (failure: NodeException) {
                return ProbeOutcome.Unavailable(
                    detail = failure.message,
                    retryable = failure.retryable,
                )
            }
        if (!result.exitedCleanly) {
            return ProbeOutcome.NotJoinable(
                "the server did not answer a Server List Ping (exit ${result.exitCode}): ${result.summary()}",
            )
        }
        val occupancy =
            PaperCommands.parseOccupancy(result.output)
                ?: return ProbeOutcome.NotJoinable(
                    "the Server List Ping reply could not be read: ${result.summary()}",
                )
        return ProbeOutcome.Joinable(online = occupancy.online, max = occupancy.max)
    }

    /**
     * Requests a world save and waits for the server's own confirmation.
     *
     * The timeout is `spec.lifecycle.drain.saveTimeout`, which the schema
     * guarantees sits below the stop grace period. On a timeout the save is
     * **not** confirmed and the container stays running.
     */
    suspend fun requestSave(
        node: Node,
        handle: WorkloadHandle,
    ): SaveOutcome {
        if (!saveConfirmable) {
            return SaveOutcome.Unconfirmable(
                "this server has persistent world data but RCON is disabled, so a completed save cannot be " +
                    "confirmed. Enable spec.network.rcon to allow this server to be drained",
            )
        }
        val request =
            ExecRequest(
                command = PaperCommands.saveAll(),
                timeout = spec.lifecycle.drain.saveTimeout,
            )
        val result =
            try {
                node.exec(handle, request)
            } catch (failure: NodeException) {
                return when (failure) {
                    // The command outran its timeout. It may well have reached
                    // the server, so the request counts as issued and must not
                    // be sent again — but it is not confirmed, and a timeout is
                    // never a reason to stop a container.
                    is NodeException.Timeout -> {
                        SaveOutcome.Unconfirmed("the save did not report completion within the save timeout")
                    }

                    else -> {
                        SaveOutcome.NotDelivered(
                            detail = failure.message,
                            retryable = failure.retryable,
                        )
                    }
                }
            }
        if (!result.exitedCleanly) {
            return SaveOutcome.Unconfirmed(
                "the save command exited ${result.exitCode}: ${result.summary()}",
            )
        }
        return if (PaperCommands.confirmsSave(result.output)) {
            SaveOutcome.Confirmed
        } else {
            // Exit code zero on its own proves only that the command ran.
            SaveOutcome.Unconfirmed(
                "the save command exited cleanly but the server did not confirm a completed save: " +
                    result.summary(),
            )
        }
    }

    private companion object {
        /**
         * A Server List Ping against localhost either answers immediately or
         * the server is not up. Short on purpose: this runs on every pass of a
         * running server.
         */
        private val PROBE_TIMEOUT: Duration = 10.seconds
    }
}

/** What a readiness probe found. */
internal sealed interface ProbeOutcome {
    /** The server answered. It is joinable, and this is its occupancy. */
    data class Joinable(
        val online: Int,
        val max: Int,
    ) : ProbeOutcome

    /** The probe ran and the server did not answer it. Still starting, or wedged. */
    data class NotJoinable(
        val detail: String,
    ) : ProbeOutcome

    /**
     * The probe could not be run at all. **Not** the same as zero players:
     * treating an unrunnable probe as "nobody is online" is how a drain stops a
     * server that still has people on it.
     */
    data class Unavailable(
        val detail: String,
        val retryable: Boolean,
    ) : ProbeOutcome
}

/** What a save request achieved. */
internal sealed interface SaveOutcome {
    /** The server reported the save completed. The only outcome that permits a stop. */
    data object Confirmed : SaveOutcome

    /**
     * The request reached the server and completion was never confirmed —
     * a timeout, a non-zero exit, or a reply that does not say the save
     * finished. The request counts as issued: do not send it again.
     */
    data class Unconfirmed(
        val detail: String,
    ) : SaveOutcome

    /** The request never went out. Safe to try again later. */
    data class NotDelivered(
        val detail: String,
        val retryable: Boolean,
    ) : SaveOutcome

    /** There is no channel through which a save could be confirmed. A human has to change the definition. */
    data class Unconfirmable(
        val detail: String,
    ) : SaveOutcome
}

/**
 * The command lines, and the patterns that decide what their output means.
 *
 * Separated from [PaperServerAgent] so an integration test can exercise the
 * parsing against captured real output without a node.
 */
internal object PaperCommands {
    /** `mc-monitor` performs a real Server List Ping and reports the handshake. */
    fun serverListPing(port: Int): List<String> =
        listOf("mc-monitor", "status", "--host", "127.0.0.1", "--port", port.toString())

    /**
     * `flush` makes the save synchronous, so the reply arrives after the write
     * rather than after the request was accepted.
     */
    fun saveAll(): List<String> = listOf("rcon-cli", "save-all", "flush")

    private val ONLINE = Regex("""\bonline=(\d+)\b""")
    private val MAX = Regex("""\bmax=(\d+)\b""")

    /**
     * The completion message, not the acknowledgement. Paper answers
     * "Saving the game (this may take a moment!)" when it starts and
     * "Saved the game" when it has finished; only the second one counts.
     */
    private val SAVE_CONFIRMED = Regex("""(?i)\bsaved the (game|world)\b""")

    data class Occupancy(
        val online: Int,
        val max: Int,
    )

    fun parseOccupancy(output: String): Occupancy? {
        val online =
            ONLINE
                .find(output)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        val max =
            MAX
                .find(output)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        if (online < 0 || max < 0) return null
        return Occupancy(online = online, max = max)
    }

    fun confirmsSave(output: String): Boolean = SAVE_CONFIRMED.containsMatchIn(output)
}

/**
 * A bounded, single-line rendering of a command's output for an operator-facing
 * message. Server output cannot contain a player name here — a Server List Ping
 * reply and a save confirmation carry none — but it can be long, and a status
 * field is not a log sink.
 */
internal fun ExecOutcome.summary(): String {
    val collapsed = output.replace(Regex("""\s+"""), " ").trim()
    return if (collapsed.length <= SUMMARY_LIMIT) collapsed else collapsed.take(SUMMARY_LIMIT) + "…"
}

private const val SUMMARY_LIMIT = 200
