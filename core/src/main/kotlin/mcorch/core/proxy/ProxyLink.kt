package mcorch.core.proxy

import mcorch.core.DestinationChoice
import mcorch.core.DestinationDecision
import mcorch.core.DestinationRequest
import mcorch.core.DrainRouter
import mcorch.core.DrainSeal
import mcorch.core.Scheduler
import mcorch.core.SealOutcome
import mcorch.core.TransferReport
import mcorch.schema.ResourceName
import mcorch.velocity.control.ControlErrorCode

/**
 * A backend's half of the conversation with the proxy in front of it.
 *
 * One object per (backend, proxy) pair per pass, built by the reconciler once it
 * has resolved which proxy fronts this server and where that proxy's control
 * endpoint is. It holds no state: everything it knows it re-reads, which is what
 * lets the level-triggered seal be level-triggered.
 */
internal class BackendLink(
    private val backend: ResourceName,
    override val proxy: ResourceName,
    /** `host:port` for the backend, as the proxy must dial it. Never a player's address. */
    private val address: String,
    private val channel: ControlChannel,
    private val scheduler: Scheduler,
    /** Everything else behind this proxy, and why each may or may not receive players. */
    private val candidates: List<mcorch.core.DestinationCandidate>,
    private val preference: List<ResourceName>,
    /**
     * Whether sealing this backend would leave the proxy with nothing admitting.
     *
     * The one ordering constraint the wire protocol cannot enforce for `:core`,
     * because no single request knows it is the last one. See [assertAdmission].
     */
    private val lastAdmitting: Boolean,
) : DrainSeal,
    DrainRouter {
    /**
     * Asserts this backend's admission, and the proxy's own first when this is the
     * last backend still admitting.
     *
     * With every backend sealed, the plugin's login-path seal has nowhere to
     * deflect a joining player to and admits them anyway — so a fleet-wide drain
     * could never reach zero on any backend. Sealing the proxy is what closes that,
     * and it is safe unconditionally: refusing a login is not disconnecting
     * anybody.
     *
     * The order is not interchangeable. Sealing this backend first opens a window
     * in which the proxy is still admitting and has nothing left to route to.
     */
    override suspend fun assertAdmission(admits: Boolean): SealOutcome {
        if (!admits && lastAdmitting) {
            when (val proxySeal = channel.assertProxyAdmission(admits = false)) {
                is ControlOutcome.Answered -> Unit
                is ControlOutcome.Refused -> return proxySeal.asSealOutcome("sealing the proxy itself")
                is ControlOutcome.Unavailable -> return proxySeal.asSealOutcome()
            }
        }
        return when (val outcome = channel.assertBackend(backend, address, admits)) {
            is ControlOutcome.Answered -> SealOutcome.Asserted(outcome.value.admitsNewPlayers)
            is ControlOutcome.Refused -> outcome.asSealOutcome("asserting the backend's admission")
            is ControlOutcome.Unavailable -> outcome.asSealOutcome()
        }
    }

    override suspend fun resolveDestination(): DestinationChoice {
        val decision =
            scheduler.selectDestination(
                DestinationRequest(
                    server = backend,
                    proxy = proxy,
                    candidates = candidates,
                    preference = preference,
                ),
            )
        return when (decision) {
            is DestinationDecision.Selected -> DestinationChoice.Chosen(decision.destination)
            is DestinationDecision.NoCapacity -> DestinationChoice.NoCapacity(decision.message)
        }
    }

    override suspend fun transfer(destination: ResourceName): TransferReport =
        when (val outcome = channel.transfer(backend, destination, TRANSFER_NOTICE)) {
            is ControlOutcome.Answered -> {
                TransferReport.Sweeping(
                    remaining = outcome.value.remaining,
                    unmoved = outcome.value.unmoved,
                    finished = outcome.value.finished,
                )
            }

            is ControlOutcome.Refused -> {
                when (outcome.code) {
                    // The destination stopped being one between step 3 and step 4.
                    // Not a failure: pick another.
                    ControlErrorCode.DESTINATION_UNKNOWN,
                    ControlErrorCode.DESTINATION_SEALED,
                    ControlErrorCode.TRANSFER_TO_SELF,
                    -> {
                        TransferReport.DestinationLost("${outcome.code}: ${outcome.problem}")
                    }

                    // Step 2 has not taken effect at the proxy this request reached.
                    // Retryable: the next pass re-asserts the seal before it gets
                    // here, which is the whole reason the seal is asserted on every
                    // pass rather than once.
                    ControlErrorCode.SOURCE_NOT_SEALED -> {
                        TransferReport.Refused("${outcome.code}: ${outcome.problem}", retryable = true)
                    }

                    else -> {
                        TransferReport.Refused("${outcome.code}: ${outcome.problem}", retryable = false)
                    }
                }
            }

            is ControlOutcome.Unavailable -> {
                TransferReport.Unavailable(outcome.detail, outcome.retryable)
            }
        }

    override suspend fun deregister(): SealOutcome =
        when (val outcome = channel.deregister(backend)) {
            is ControlOutcome.Answered -> {
                SealOutcome.Asserted(admits = false)
            }

            is ControlOutcome.Refused -> {
                when (outcome.code) {
                    // Somebody is connected through the proxy that the ping did not
                    // see. Retryable and never overridable: there is no force flag,
                    // and asking for one would be asking to spell the thing this
                    // endpoint exists to make unspellable.
                    ControlErrorCode.BACKEND_OCCUPIED -> {
                        SealOutcome.Refused(outcome.problem, retryable = true)
                    }

                    // Already gone. The loop may re-enter any state any number of
                    // times, and a deregistration that already happened is the state
                    // that was wanted.
                    ControlErrorCode.BACKEND_UNKNOWN -> {
                        SealOutcome.Asserted(admits = false)
                    }

                    else -> {
                        outcome.asSealOutcome("deregistering the backend")
                    }
                }
            }

            is ControlOutcome.Unavailable -> {
                outcome.asSealOutcome()
            }
        }

    override suspend fun reregister(): SealOutcome =
        // Registered *and admitting*: this is the abort path, and a parked drain is
        // not going to move those players, so leaving the backend sealed would leave
        // a running server no player can reach.
        when (val outcome = channel.assertBackend(backend, address, admits = true)) {
            is ControlOutcome.Answered -> SealOutcome.Asserted(outcome.value.admitsNewPlayers)
            is ControlOutcome.Refused -> outcome.asSealOutcome("re-registering the backend")
            is ControlOutcome.Unavailable -> outcome.asSealOutcome()
        }

    override suspend fun observedPlayers(): Int? =
        when (val outcome = channel.state()) {
            is ControlOutcome.Answered -> outcome.value.backend(backend)?.players

            // Corroboration is optional by construction: a proxy that cannot answer
            // costs a log line, never a decision.
            else -> null
        }

    private companion object {
        /**
         * What a player is told before they are moved (`SKILL.md` step 4).
         *
         * MiniMessage, because Velocity renders it. It names the server they are
         * leaving and nothing about them — there is no field in this file that
         * could hold a player's name.
         */
        private const val TRANSFER_NOTICE =
            "<yellow>This server is shutting down. Moving you to another one — nothing is lost.</yellow>"
    }
}

/**
 * The proxy's half of its **own** drain.
 *
 * A [DrainSeal] and deliberately not a [DrainRouter]: a proxy can stop admitting
 * logins, and it has nowhere to send the players already connected to it, because
 * a fleet has one front door. Its drain therefore keeps the standalone shape —
 * seal, then wait for the last player to log off — and there is no cross-server
 * sequencing anywhere in it.
 */
internal class ProxySelfLink(
    private val channel: ControlChannel,
) : DrainSeal {
    override suspend fun assertAdmission(admits: Boolean): SealOutcome =
        when (val outcome = channel.assertProxyAdmission(admits)) {
            is ControlOutcome.Answered -> SealOutcome.Asserted(outcome.value.admitsNewPlayers)
            is ControlOutcome.Refused -> outcome.asSealOutcome("asserting the proxy's own admission")
            is ControlOutcome.Unavailable -> outcome.asSealOutcome()
        }
}

private fun ControlOutcome.Refused.asSealOutcome(what: String): SealOutcome =
    SealOutcome.Refused(
        detail = "the proxy refused $what ($code): $problem",
        // A refusal is the proxy telling this caller its own ordering is wrong, and
        // that does not fix itself — but it is still retryable, because "stop
        // trying" on a drain step is how a container becomes undeletable on a fault
        // the next pass could have cleared. The narrow bucket stays narrow.
        retryable = true,
    )

private fun ControlOutcome.Unavailable.asSealOutcome(): SealOutcome =
    SealOutcome.Unavailable(detail = detail, retryable = retryable)
