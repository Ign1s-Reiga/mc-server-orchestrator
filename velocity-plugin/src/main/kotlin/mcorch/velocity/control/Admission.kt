package mcorch.velocity.control

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The seal: who is allowed to be *sent* somewhere, held as level-triggered state.
 *
 * ## Why this is not the registered-server map
 *
 * The obvious way to stop Velocity sending players to a backend is to take it
 * out of the registry. That is also drain step 6, and doing step 6 at step 2
 * disconnects everyone still connected — `failure-modes.md` item 3. So the seal
 * lives here, entirely beside the registry: sealing a backend changes nothing
 * Velocity knows about it and touches no existing connection. It only changes
 * what the two routing listeners decide for the *next* player.
 *
 * ## Why it is not persisted
 *
 * Nothing here is written to disk and nothing survives a proxy restart. That is
 * the point of a level-triggered assertion: `:core` re-states the whole desired
 * admission of every backend on every reconcile pass, so a restarted proxy is
 * corrected on the next pass and an aborted drain restores joins without anyone
 * having to remember to un-seal anything. Persisting it would invert that — a
 * backend sealed by an orchestrator that then died would stay sealed forever,
 * with nothing left in the system that knew to lift it.
 *
 * The gap is real and is stated rather than papered over: between a proxy
 * restart and the next pass, a sealed backend admits again. A Velocity restart
 * drops every player connection anyway, so the backend it re-admits to is one
 * whose drain has to start over regardless, and `:core` learns the seal is not
 * in force by reading it back rather than by assuming its last write stuck.
 *
 * ## Names
 *
 * Velocity resolves server names case-insensitively, so a seal keyed on the
 * exact string `:core` happened to send would be a seal that misses when
 * something else spells the same backend differently. Everything here is keyed
 * on the lowercase form.
 */
public class AdmissionRegistry {
    private val sealedBackends = ConcurrentHashMap.newKeySet<String>()
    private val counters = ConcurrentHashMap<String, Counters>()

    @Volatile
    private var proxyAdmitsLogins = true

    private val refusedLogins = AtomicInteger()

    /** Whether new players may be routed to [backend]. Unknown backends admit: absence is not a seal. */
    public fun admits(backend: String): Boolean = key(backend) !in sealedBackends

    /**
     * States whether [backend] admits new players. Idempotent, and the only way
     * the seal ever changes.
     */
    public fun assertAdmission(
        backend: String,
        admits: Boolean,
    ) {
        val key = key(backend)
        if (admits) sealedBackends.remove(key) else sealedBackends.add(key)
    }

    /** Drops a deregistered backend's seal, so a later re-registration starts from what its assert says. */
    public fun forget(backend: String) {
        val key = key(backend)
        sealedBackends.remove(key)
        counters.remove(key)
    }

    /** Whether the proxy itself is accepting logins. The proxy's own drain, `ProxyDrainSpec`. */
    public fun proxyAdmits(): Boolean = proxyAdmitsLogins

    public fun assertProxyAdmission(admits: Boolean) {
        proxyAdmitsLogins = admits
    }

    /** A login the proxy refused because it is sealed. Refusing to admit is not disconnecting anybody. */
    public fun recordRefusedLogin() {
        refusedLogins.incrementAndGet()
    }

    public fun refusedLogins(): Int = refusedLogins.get()

    /** A player already in-game was stopped from switching onto a sealed backend. */
    public fun recordRefusedSwitch(backend: String) {
        mutableCounters(backend).refusedSwitches.incrementAndGet()
    }

    /** A joining player was sent to a different backend because their first choice was sealed. */
    public fun recordDeflectedJoin(backend: String) {
        mutableCounters(backend).deflectedJoins.incrementAndGet()
    }

    /**
     * A joining player was let onto a sealed backend because nothing else would
     * take them.
     *
     * This is the seal admitting it leaked, and it is reported rather than hidden
     * because the alternative — refusing the login path — is a kick, and `:core`
     * needs to know the difference between "sealed and holding" and "sealed and
     * still receiving players" when it decides whether a transfer sweep has
     * converged.
     */
    public fun recordAdmittedWithoutAlternative(backend: String) {
        mutableCounters(backend).admittedWithoutAlternative.incrementAndGet()
    }

    /** What the seal has done to one backend so far. Zeroes for a backend nothing has happened to. */
    public fun counters(backend: String): SealCounters {
        val current = counters[key(backend)] ?: return SealCounters(0, 0, 0)
        return SealCounters(
            refusedSwitches = current.refusedSwitches.get(),
            deflectedJoins = current.deflectedJoins.get(),
            admittedWithoutAlternative = current.admittedWithoutAlternative.get(),
        )
    }

    private fun mutableCounters(backend: String): Counters = counters.computeIfAbsent(key(backend)) { Counters() }

    private fun key(backend: String): String = backend.lowercase(Locale.ROOT)

    private class Counters {
        val refusedSwitches = AtomicInteger()
        val deflectedJoins = AtomicInteger()
        val admittedWithoutAlternative = AtomicInteger()
    }
}

/** What the seal has actually done to one backend, as counts. Reported on every read. */
public data class SealCounters(
    val refusedSwitches: Int,
    val deflectedJoins: Int,
    val admittedWithoutAlternative: Int,
)

/**
 * The two routing decisions a seal makes, as pure functions.
 *
 * They are here rather than inside the Velocity listeners because they are the
 * part worth testing and the listeners are the part that cannot be. A listener
 * is then four lines: read the event, call one of these, apply the answer.
 *
 * ## Neither of these can produce a disconnect
 *
 * That is not an accident of the current implementation. Velocity's
 * `ServerPreConnectEvent.denied()` is safe on an in-game switch — the player
 * stays on the server they are already on — but on the *login* path it strands
 * the client on "Connecting to server" until it times out (PaperMC/Velocity
 * issue 689). A seal that used it there would be a seal that disconnects the
 * players it is meant to protect. So [onInitialChoice] never denies. Its worst
 * case is [InitialChoice.AdmitAnyway]: a player joins a sealed backend, the fact
 * is counted, and the transfer sweep picks them up. That is recoverable.
 * Stranding them is not.
 */
public object SealPolicy {
    /**
     * Where a joining player should go, given the proxy's own first choice.
     *
     * [candidates] is every registered backend, in a deterministic order.
     * Preferring a candidate over the sealed choice is the whole of "stop new
     * joins" on the login path.
     */
    public fun onInitialChoice(
        chosen: String?,
        candidates: List<String>,
        admission: AdmissionRegistry,
    ): InitialChoice {
        if (chosen == null) return InitialChoice.Keep
        if (admission.admits(chosen)) return InitialChoice.Keep
        val alternative =
            candidates.firstOrNull { !it.equals(chosen, ignoreCase = true) && admission.admits(it) }
                ?: return InitialChoice.AdmitAnyway
        return InitialChoice.Redirect(alternative)
    }

    /**
     * Whether a player may switch to [target].
     *
     * [playerIsConnected] is load-bearing. Refusing is only safe once the player
     * has a server to stay on; on the login path the same refusal strands them,
     * so this returns [SwitchDecision.Allow] there and leaves the deflection to
     * [onInitialChoice], which has already run.
     */
    public fun onServerSwitch(
        target: String,
        playerIsConnected: Boolean,
        admission: AdmissionRegistry,
    ): SwitchDecision =
        when {
            admission.admits(target) -> SwitchDecision.Allow

            // Sealed, but refusing would strand them: they have no server to be left
            // on. Distinct from Allow so the listener can count it — this is the seal
            // leaking, and it fires on Velocity's own fallback reconnect, where a
            // player whose backend just died is routed onto a sealed one.
            !playerIsConnected -> SwitchDecision.AllowSealed

            else -> SwitchDecision.Refuse
        }
}

/** What to do with a joining player's server choice. There is no "deny" and there will not be one. */
public sealed interface InitialChoice {
    /** Leave the proxy's choice alone: it admits, or there was no choice to begin with. */
    public data object Keep : InitialChoice

    /** Send them to this backend instead, because their first choice is sealed. */
    public data class Redirect(
        public val backend: String,
    ) : InitialChoice

    /** The choice is sealed and nothing else admits. Let them in and count it. See [SealPolicy]. */
    public data object AdmitAnyway : InitialChoice
}

/**
 * Whether a server switch goes ahead.
 *
 * [Refuse] leaves the player on their current server. [AllowSealed] is a seal that
 * could not hold without stranding somebody — permitted, and counted, because
 * reporting a leak is the whole reason `:core` can trust the ones that did hold.
 */
public enum class SwitchDecision {
    Allow,
    AllowSealed,
    Refuse,
}
