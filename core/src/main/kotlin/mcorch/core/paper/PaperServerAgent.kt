package mcorch.core.paper

import mcorch.core.ExecOutcome
import mcorch.core.ExecRequest
import mcorch.core.ExecTimeout
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
    fun contractOf(observation: WorkloadObservation.Present): WorkloadContract {
        val worldData = Labels.booleanValue(observation.labels, Labels.WORLD_DATA)
        val saveChannel = Labels.booleanValue(observation.labels, Labels.SAVE_CONFIRMABLE)
        return WorkloadContract(
            // An absent label means "this workload does not say", which is not
            // the same as "no" — and there is no second source worth asking.
            // The definition is the thing being edited; the last observed
            // storage status is *computed from* the definition every pass, so
            // it agrees with the edit within one pass of it landing. Both would
            // answer "ephemeral" for a container holding a world, the drain
            // would find nothing to flush, and the container would stop with
            // the world in it. So this is the safe side and nothing else:
            // CLAUDE.md invariant 2 says default to persistent.
            holdsWorldData = worldData ?: true,
            // The save channel has no safe side in the same way: assuming one
            // exists costs a retryable failure, assuming it does not costs a
            // server nobody can drain. The definition is the better guess.
            saveConfirmable = saveChannel ?: declaresSaveChannel,
            observed = worldData != null && saveChannel != null,
        )
    }

    /**
     * Asks the server whether it is accepting players, and how many it has.
     *
     * The `try` around the request is the rule [Node] states for every construction
     * site, applied here where **no input can reach it**: [PROBE_TIMEOUT] is a
     * constant, and the one definition-fed input — `spec.network.port` — becomes a
     * `toString()`, which `ExecRequest`'s `init` has nothing to refuse. So it is a
     * guard rather than a fix, and it is written down as one instead of being left
     * out: the day a probe timeout comes off a definition the way `saveTimeout` does,
     * the site that needs classifying already has it, and a rule with an exception at
     * two of its three sites is a rule the fourth site is not going to keep.
     */
    suspend fun probe(
        node: Node,
        handle: WorkloadHandle,
    ): ProbeOutcome {
        val request =
            try {
                ExecRequest(
                    command = PaperCommands.serverListPing(spec.network.port),
                    timeout = ExecTimeout.of(PROBE_TIMEOUT),
                )
            } catch (rejected: IllegalArgumentException) {
                return ProbeOutcome.Unavailable(detail = unbuildableProbe(rejected), retryable = false)
            }
        val result =
            try {
                node.exec(handle, request)
            } catch (failure: NodeException) {
                return probeFailed(failure)
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
     * What a probe that never produced a result means.
     *
     * The whole distinction is between *the node could not be asked* and *the
     * node was asked, ran the probe, and the server did not answer it*. They
     * arrive as the same exception type because they arrive as the same gRPC
     * code, and reading the second as the first is what put a healthy runtime on
     * observed status as `RUNTIME_UNREACHABLE` while a Paper server was doing
     * nothing worse than generating its world.
     *
     * A command timeout is therefore [ProbeOutcome.NotJoinable] — the same
     * answer as `mc-monitor` exiting non-zero, and for the same reason: the
     * probe ran and the server did not reply to it. That single mapping is
     * enough for both cases the caller has to tell apart, because the caller
     * already tells them apart by the clock rather than by the probe:
     *
     * - **Still starting.** The server stays `STARTING` with this as the
     *   readiness detail, and `spec.lifecycle.startupTimeout` is what eventually
     *   fails it. Nothing else changes.
     * - **Past startup, previously running.** The same detail is past the
     *   startup timeout, so it surfaces as a retryable `READINESS_TIMEOUT` — a
     *   server that has stopped answering `mc-monitor` may be frozen
     *   (`failure-modes.md`, "agent responds but the server does not"), and that
     *   is what a readiness failure on a long-running server says. It stays
     *   retryable and nothing restarts it: a restart is a stop path, a stop path
     *   drains, and a drain cannot confirm zero players on a server that is not
     *   answering.
     *
     * Neither is a reason to stop anything, and neither is a player count. The
     * drain reads any non-[ProbeOutcome.Joinable] answer as "cannot confirm zero
     * players" and aborts, so a probe that times out can never be mistaken for
     * an empty server whichever of the two it was.
     */
    private fun probeFailed(failure: NodeException): ProbeOutcome {
        if (failure is NodeException.Timeout && failure.commandTimeout) {
            return ProbeOutcome.NotJoinable(
                "the server did not answer a Server List Ping within ${PROBE_TIMEOUT.inWholeSeconds}s, and the " +
                    "node stopped the probe at that timeout. The node answered promptly, so it is reachable: " +
                    "this is the server not replying — still generating its world, or wedged",
            )
        }
        return ProbeOutcome.Unavailable(
            detail = failure.message,
            retryable = failure.retryable,
        )
    }

    /**
     * Requests a world save and waits for the server's own confirmation.
     *
     * The timeout is `spec.lifecycle.drain.saveTimeout`, which the schema
     * guarantees sits below the stop grace period. On a timeout the save is
     * **not** confirmed and the container stays running.
     *
     * It goes through [ExecTimeout], which bounds it — the thirtieth audit's second
     * finding. This is the *longer* of the two durations a definition hands the
     * node, and it becomes `execSync`'s gRPC deadline directly, so a row that never
     * came through `PaperServerReader` parked a reconcile worker in `save-all flush`
     * for as long as it liked. The cap can only ever make a save go unconfirmed
     * earlier, and an unconfirmed save is a container this orchestrator does not
     * stop — the safe direction, and the reason a cap is right here where a refusal
     * would abort the drain. See `ExecTimeoutCeiling` for why it is not the same
     * bound as the stop grace period's, from the same field.
     *
     * A cap has no answer for the *bottom* of the range, and that half is
     * [unbuildableSave]: a `saveTimeout` of zero or less is refused by
     * `ExecRequest`'s own `init`, and this is the site that turns that refusal into a
     * recorded drain failure rather than an exception nobody classifies.
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
            try {
                ExecRequest(
                    command = PaperCommands.saveAll(),
                    timeout = ExecTimeout.of(spec.lifecycle.drain.saveTimeout),
                )
            } catch (rejected: IllegalArgumentException) {
                return unbuildableSave(rejected)
            }
        val result =
            try {
                node.exec(observation.handle, request)
            } catch (failure: NodeException) {
                return when (failure) {
                    // The command outran its timeout. It may well have reached
                    // the server, so the request counts as issued and must not
                    // be sent again — but it is not confirmed, and a timeout is
                    // never a reason to stop a container.
                    //
                    // `commandTimeout` is deliberately *not* consulted here, and
                    // this is the asymmetry with `probe` above. A probe is a
                    // read, so knowing whose clock ran out changes what may be
                    // concluded from the silence. A save is a side effect: once
                    // the exec was dispatched the request may have reached the
                    // server whether the node cut it short or never answered, so
                    // both go in the bucket that is never re-sent. Refining this
                    // would only ever move a case *out* of that bucket, which is
                    // a second `save-all flush` on a live server.
                    is NodeException.Timeout -> {
                        SaveOutcome.Unconfirmed("the save did not report completion within the save timeout")
                    }

                    // **This branch is the site [NodeDispatch] was written for, and
                    // it is deliberately not wired to it yet.**
                    //
                    // `NotDelivered` leaves `saveRequestedAt` unset, which is what
                    // permits a later pass to send a second `save-all flush`. The
                    // justification is *"no exec was dispatched, so nothing reached
                    // the server"* — and what is actually being asked is the
                    // subclass. That is right for the refusals `LocalNode` raises
                    // above its own call (`requireContainer`, an unbuildable
                    // request) and it is an assumption for the rest: a
                    // `CriException.RuntimeFailure` on an `ExecSync` arrives as
                    // `Busy` and says nothing about whether the command ran, and a
                    // `Cancelled` arrives as `Rejected` after the exec left this
                    // process.
                    //
                    // `failure.dispatch` answers it, and the change it would make is
                    // to move the `UNKNOWN` cases into `Unconfirmed` — the bucket
                    // that is never re-sent. That is a **drain guard**, so it goes
                    // through `drain-auditor` rather than riding along with the
                    // taxonomy that makes it expressible: the same edit also decides
                    // what a `DRAIN_SAVE_TIMEOUT` wedge costs a server whose RCON
                    // hiccuped once, which is the trade item 3 of the danger
                    // patterns is about and is not this change's to take.
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
     * A save request this definition cannot express, recorded as a failure instead
     * of thrown.
     *
     * ## What it is for
     *
     * `ExecRequest`'s `init` refuses a non-positive timeout, and this one is
     * `spec.lifecycle.drain.saveTimeout` — operator data that also arrives from a
     * store row, which `DefinitionCodec` does not re-validate. `SpecBounds` caps that
     * field on the way out of the store and deliberately does **not** floor it: a row
     * holding `saveTimeout = 0` beside `stopGracePeriod = 30s` satisfies
     * `SpecInvariants.stopGraceProblem` exactly, and flooring the save to one second
     * would push the pair's minimum above the grace period declared beside it. So
     * zero reaches here, by design, and this is where it stops.
     *
     * Uncaught, the exception is built *outside* the `try` that catches
     * [NodeException], passes `Reconciler`'s two typed catches, and lands in
     * `ReconcileLoop.work`'s `catch (Throwable)` as a bare requeue with **no status
     * write**: the drain is never recorded as failed, nothing raises
     * `NEEDS_ATTENTION`, and the server cannot be deleted. That is CLAUDE.md's
     * *"permanent failures surface on the server's observed status"* not happening.
     *
     * ## `NotDelivered`, and permanently
     *
     * `NotDelivered` is the truthful case: no exec was dispatched, so nothing reached
     * the server and the never-re-send wedge ([SaveOutcome.Unconfirmed]) must not be
     * armed — `saveRequestedAt` stays null and a repaired definition saves normally.
     *
     * It is the **permanent** side of that case, which is the narrow bucket this
     * project keeps narrow, so the evidence is worth naming: the value is in hand,
     * nothing was asked of any third party, and every later pass rebuilds the same
     * request from the same field. Asking again is not a different question.
     *
     * The gate that permanence arms is `Reconciler.isBlockedByPermanentFailure`, and
     * it is liftable **here** in a way it is not on the proxy's control path: the
     * field is on *this* server's own definition, so the edit that repairs it is the
     * edit that bumps this server's generation and resumes its passes. And
     * `permanentFailureStopsPasses` exempts a terminating definition, so a delete
     * keeps reconciling — it still will not stop the container, because no save was
     * confirmed (CLAUDE.md invariant 3), which is the correct answer and not a
     * consequence of the class.
     */
    private fun unbuildableSave(rejected: IllegalArgumentException): SaveOutcome.NotDelivered =
        SaveOutcome.NotDelivered(
            detail =
                "no world save could be requested, because spec.lifecycle.drain.saveTimeout is not a duration a " +
                    "command can be run with: ${rejected.message}. Nothing was sent to the server, which keeps " +
                    "running with its players on it. Correct that field and the drain carries on from here",
            retryable = false,
        )

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

    /**
     * Every answer that is not a player count.
     *
     * The two below differ in what they say about the *node*, which is what
     * bring-up needs in order to report a starting server differently from a
     * sick runtime. They do not differ at all in what they say about **who is
     * online**, which is the only question a drain asks — and that is why this
     * type exists rather than two sibling cases.
     *
     * A drain matches on this, once. Treating an unanswered probe as "nobody is
     * online" is how a drain stops a server that still has people on it, and the
     * reason to make the two indistinguishable *here* is that a distinction
     * maintained by convention across two branches is a distinction that
     * eventually diverges under an edit aimed at only one of them.
     */
    sealed interface Unanswered : ProbeOutcome {
        val detail: String

        /** Whether asking again could plausibly get a different answer. */
        val retryable: Boolean
    }

    /** The probe ran and the server did not answer it. Still starting, or wedged. */
    data class NotJoinable(
        override val detail: String,
    ) : Unanswered {
        // The server may yet answer: it is still generating a world, or it is
        // frozen and somebody will restart it. Nothing about a silent server
        // makes asking again pointless.
        override val retryable: Boolean get() = true
    }

    /** The probe could not be run at all — the node refused it, or never answered. */
    data class Unavailable(
        override val detail: String,
        override val retryable: Boolean,
    ) : Unanswered
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
     * **`flush` is load-bearing and must not be dropped.**
     *
     * Verified against a real Paper server: `save-all flush` blocks until the
     * write completes and only then replies, while plain `save-all` replies
     * with the *byte-identical* `Saved the game` about six seconds before the
     * write finishes. Nothing downstream can tell the two apart —
     * [SAVE_CONFIRMED] matches both, by construction — so this argument is the
     * only thing standing between a confirmed save and a container stopped
     * mid-write.
     *
     * `PaperCommandsTest` pins the exact argument list. If you are here to
     * simplify this call, that test is the conversation.
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
     * A whitelist rather than a truncation of whatever came back, because
     * neither of these outputs is ours to repeat. `mc-monitor status` prints one
     * summary line — `<host>:<port> : version=Paper 1.21.4 online=0 max=20
     * motd='...'` — which carries no player sample, but `motd` is free text an
     * operator can put anything in, and the version token contains a space, so
     * nothing may parse it positionally either. A console reply is whatever the
     * server or a plugin printed. And the SLP payload behind the summary does
     * carry a `players.sample` block of names and UUIDs, which is one flag or
     * one tool change away from reaching here.
     *
     * CLAUDE.md bans names, UUIDs and addresses from logs and from status, so
     * nothing is ever echoed: a recognised phrase is reported by name and
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
 * What a probe request that could not be built says to an operator.
 *
 * Shared by both agents' probes so the sentence is one thing rather than two that
 * drift. It is deliberately vaguer than the save's: a probe's inputs are a constant
 * and a port, so there is no single field to point at, and pointing at the wrong one
 * is worse than describing what happened.
 *
 * No branch that reaches this exists today — see `PaperServerAgent.probe` — so this
 * is a sentence nothing prints, kept because the alternative is a construction site
 * with no classification at all.
 */
internal fun unbuildableProbe(rejected: IllegalArgumentException): String =
    "the readiness probe could not be built from this server's definition: ${rejected.message}. Nothing was run " +
        "inside the container, so this says nothing about whether the server is joinable or who is on it"

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
