package mcorch.core.paper

import mcorch.core.ExecOutcome
import mcorch.core.ExecRequest
import mcorch.core.Labels
import mcorch.core.Node
import mcorch.core.NodeException
import mcorch.core.WorkloadHandle
import mcorch.core.WorkloadObservation
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
     * Whether the *declared* server holds world data that must be saved before
     * it stops.
     *
     * False only for `ephemeral` storage, which the operator has to ask for by
     * name and which by definition has nothing worth flushing. This describes
     * the definition, not whatever is running — use [contractOf] for that.
     */
    val declaresWorldData: Boolean get() = spec.storage is StorageSpec.Persistent

    /**
     * Whether the *declared* server has any channel through which a save can be
     * *confirmed*.
     *
     * Confirmation needs a reply, and the only thing that replies is RCON.
     * Without it the loop can push a save request into the console and learn
     * nothing about whether it finished — which is precisely the conflation the
     * drain protocol forbids. A server with world data and no RCON therefore
     * cannot be drained, and the loop says so rather than stopping it anyway.
     */
    val declaresSaveChannel: Boolean get() = spec.network.rcon is RconSpec.Enabled

    /**
     * What the container that is actually running was built with.
     *
     * A drain is conducted against the container, never against the definition
     * as it reads today. Two edits make the difference load-bearing:
     *
     * - `storage.mode: persistent` → `ephemeral` would otherwise make the drain
     *   believe there is no world to flush and stop a container that holds one.
     * - enabling `network.rcon` would otherwise make the drain believe it can
     *   confirm a save through a listener the running container never started.
     *
     * Both are recreate-level changes, so the drain that applies them runs
     * against the *old* container. The facts come off the workload's own labels;
     * a workload created before those labels existed reports none, and the
     * definition is the only thing left to go on.
     */
    fun contractOf(
        observation: WorkloadObservation.Present,
        storageWasPersistent: Boolean? = null,
    ): WorkloadContract {
        val worldData = Labels.booleanValue(observation.labels, Labels.WORLD_DATA)
        val saveChannel = Labels.booleanValue(observation.labels, Labels.SAVE_CONFIRMABLE)
        return WorkloadContract(
            // An absent label means "this workload does not say", which is not
            // the same as "no". Falling back to the definition would hand the
            // decision straight back to the edit this is meant to be defending
            // against: on a `persistent` → `ephemeral` change it would read
            // false, the drain would find nothing to flush, and the container
            // would stop with a world in it. So the fallback is the last
            // observation written *before* the edit, and failing that the safe
            // side — CLAUDE.md invariant 2 says default to persistent.
            holdsWorldData = worldData ?: storageWasPersistent ?: true,
            // The save channel has no safe side in the same way: assuming one
            // exists costs a retryable failure, assuming it does not costs a
            // server nobody can drain. The definition is the better guess.
            saveConfirmable = saveChannel ?: declaresSaveChannel,
            observed = worldData != null && saveChannel != null,
        )
    }

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
                "the server did not answer a Server List Ping (${result.diagnose()})",
            )
        }
        val occupancy =
            PaperCommands.parseOccupancy(result.output)
                ?: return ProbeOutcome.NotJoinable(
                    "the Server List Ping reply carried no occupancy report (${result.diagnose()})",
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
        observation: WorkloadObservation.Present,
        contract: WorkloadContract = contractOf(observation),
    ): SaveOutcome {
        if (!contract.saveConfirmable) {
            return SaveOutcome.Unconfirmable(noSaveChannel(contract))
        }
        val request =
            ExecRequest(
                command = PaperCommands.saveAll(),
                timeout = spec.lifecycle.drain.saveTimeout,
            )
        val result =
            try {
                node.exec(observation.handle, request)
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
            // A non-zero exit is the RCON client's verdict, not the server's.
            // It is "unconfirmed" — the bucket that is never retried — only if
            // something in the output shows the request actually reached the
            // server. A client that could not connect, was reset, or was
            // refused never delivered anything, and refusing to try that again
            // wedges the server for good over what is usually a hiccup.
            return if (PaperCommands.reachedServer(result.output)) {
                SaveOutcome.Unconfirmed(
                    "the server acknowledged the save and the save command then exited ${result.exitCode} " +
                        "(${result.diagnose()})",
                )
            } else {
                SaveOutcome.NotDelivered(
                    detail =
                        "the RCON client exited ${result.exitCode} without reaching the server " +
                            "(${result.diagnose()}); no save request was delivered" + rconHint(contract),
                    retryable = true,
                )
            }
        }
        return if (PaperCommands.confirmsSave(result.output)) {
            SaveOutcome.Confirmed
        } else {
            // Exit code zero on its own proves only that the command ran. The
            // client connected and got *something* back, so the request did
            // reach the server: this one stays unconfirmed rather than
            // undelivered.
            SaveOutcome.Unconfirmed(
                "the save command exited cleanly but the server did not confirm a completed save " +
                    "(${result.diagnose()})",
            )
        }
    }

    /**
     * Why this container cannot report a completed save, and what an operator
     * can actually do about it.
     *
     * The honest part is the second half. Enabling `spec.network.rcon` changes
     * the *next* container; it does nothing for the one that is running, and
     * that container cannot be recreated without a drain, which is the thing
     * that needs the save channel. So the way out is not an edit — it is a human
     * saving and stopping the server themselves, after which the loop observes a
     * stopped container and finishes the teardown on its own.
     */
    private fun noSaveChannel(contract: WorkloadContract): String {
        val cause =
            if (contract.observed && declaresSaveChannel) {
                "the running container was created with RCON disabled, so enabling spec.network.rcon has not " +
                    "reached it — that setting applies to the next container, and this one cannot be replaced " +
                    "until it has been drained"
            } else {
                "this server has persistent world data and RCON is disabled, so nothing can reply that a save " +
                    "completed"
            }
        return "$cause. The container keeps running and will not be stopped by the orchestrator. To retire it, " +
            "save the world and stop the container yourself; the teardown completes on its own once a stopped " +
            "container is observed. To keep it, revert spec.network.rcon and the server returns to running"
    }

    /**
     * The one case a connection failure cannot be told apart from a missing
     * listener: a workload that carries no record of what it was built with.
     */
    private fun rconHint(contract: WorkloadContract): String =
        if (contract.observed) {
            ""
        } else {
            ". This container carries no record of its save channel, so it may have been created before " +
                "spec.network.rcon was enabled — in which case no edit can drain it and it has to be saved " +
                "and stopped by hand"
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

/**
 * What the running container was built with, which is what a drain must act on.
 *
 * [observed] says where the facts came from: true when the workload carried them
 * itself, false when the definition was the only thing left to ask. The
 * difference matters for what an operator is told — a guess derived from an
 * edited definition should not be reported as an observation.
 */
internal data class WorkloadContract(
    val holdsWorldData: Boolean,
    val saveConfirmable: Boolean,
    val observed: Boolean,
)

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

    /**
     * Either of the server's two replies. This is not evidence the save
     * finished — that is [SAVE_CONFIRMED] — it is evidence the request got as
     * far as the server at all, which is what decides whether re-sending it
     * later is safe.
     */
    private val SAVE_ACKNOWLEDGED = Regex("""(?i)\bsav(ing|ed) the (game|world)\b""")

    /**
     * Client-side failures worth telling an operator about, matched as whole
     * phrases against the output.
     *
     * A whitelist rather than a truncation of whatever came back. Output from a
     * Minecraft server can carry player names — an SLP reply has a
     * `players.sample` block of names and UUIDs, and any console reply is the
     * server's text — and CLAUDE.md bans those from logs and from status. So
     * nothing here is ever echoed: a recognised phrase is reported by name and
     * everything else is reported as a byte count.
     */
    private val DIAGNOSTICS =
        listOf(
            "connection refused",
            "connection reset",
            "no route to host",
            "network is unreachable",
            "no such host",
            "i/o timeout",
            "context deadline exceeded",
            "broken pipe",
            "authentication failed",
            "invalid password",
            "unknown command",
            "permission denied",
            "unexpected eof",
        )

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

    /** Whether the request got as far as the server, whatever the client then did. */
    fun reachedServer(output: String): Boolean = SAVE_ACKNOWLEDGED.containsMatchIn(output)

    /** The recognised phrases in [output], in the order they are listed. Never any of the output itself. */
    fun diagnostics(output: String): List<String> {
        val haystack = output.lowercase()
        return DIAGNOSTICS.filter { it in haystack }
    }
}

/**
 * What went wrong with a command, said without repeating what it printed.
 *
 * Nothing an operator sees here comes out of the container. Recognised failure
 * phrases are reported by name and everything else is reported as a size,
 * because output from a Minecraft server can carry player names, UUIDs and
 * addresses — an SLP reply carries a `players.sample` block of exactly that —
 * and this string ends up on observed status, in the API, and in a log line.
 */
internal fun ExecOutcome.diagnose(): String {
    val recognised = PaperCommands.diagnostics(output)
    val detail =
        if (recognised.isEmpty()) {
            "no recognised diagnostic; ${stdout.length} bytes on stdout, ${stderr.length} on stderr, " +
                "not repeated here"
        } else {
            recognised.joinToString("; ")
        }
    return "exit $exitCode: $detail"
}
