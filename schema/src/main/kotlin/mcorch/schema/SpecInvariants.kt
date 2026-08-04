package mcorch.schema

import kotlin.time.Duration

/**
 * Cross-field rules that would lose data if they were only checked at parse
 * time. They are stated once and enforced twice: the parser reports them as
 * violations (so they aggregate with everything else), and the constructors
 * refuse to build an object that breaks them (so no other module can invent
 * one).
 *
 * They live in one object, across kinds, so the two enforcement paths for a rule
 * cannot drift and so a later kind reuses a rule rather than restating it —
 * [heapProblem] already applies to any JVM workload this orchestrator runs.
 */
internal object SpecInvariants {
    fun heapProblem(
        heapMax: MemoryQuantity,
        containerMemory: MemoryQuantity,
    ): String? {
        val allowed = JvmHeapPolicy.maxAllowedHeap(containerMemory)
        return if (heapMax <= allowed) {
            null
        } else {
            "must leave headroom below the container memory limit: with " +
                "spec.resources.memory=${containerMemory.render()} the largest heap is " +
                "${allowed.render()}, found ${heapMax.render()}. A heap sized at the container limit is " +
                "OOM-killed mid-tick with the world unsaved"
        }
    }

    fun stopGraceProblem(
        stopGracePeriod: Duration,
        saveTimeout: Duration,
    ): String? {
        val minimum = saveTimeout + PaperServerDefaults.MIN_STOP_GRACE_MARGIN
        return if (stopGracePeriod >= minimum) {
            null
        } else {
            "must exceed spec.lifecycle.drain.saveTimeout (${DurationFormat.render(saveTimeout)}) by at least " +
                "${DurationFormat.render(PaperServerDefaults.MIN_STOP_GRACE_MARGIN)}, so at least " +
                "${DurationFormat.render(minimum)}, found ${DurationFormat.render(stopGracePeriod)}. " +
                "A grace period shorter than the save timeout kills the container part-way through the save"
        }
    }

    /**
     * The proxy's two ports must differ, and so must the two host ports it
     * publishes.
     *
     * Not a data-loss rule like the two above, but a proxy whose control
     * endpoint collides with its player listener is a proxy that either does not
     * start or answers `seal` requests on the port players are connected to.
     * Both are found at parse time; the `require` is what stops a fixture or a
     * hand-repaired row expressing it.
     */
    fun proxyPortProblem(
        network: ProxyNetworkSpec,
        control: ControlEndpointSpec,
    ): String? =
        when {
            network.port == control.port -> {
                "must differ from spec.network.port, both are ${control.port}"
            }

            control.hostPort != null && control.hostPort == network.hostPort -> {
                "hostPort must differ from spec.network.hostPort, both are ${control.hostPort}"
            }

            else -> {
                null
            }
        }
}
