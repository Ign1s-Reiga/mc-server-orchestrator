package mcorch.core

import mcorch.schema.NodeName
import mcorch.schema.ResourceName
import org.slf4j.Logger
import kotlin.time.Duration

/**
 * Typed records for the log lines whose arguments are positionally swappable.
 *
 * ## The failure mode, and why a test is the wrong remedy
 *
 * `LOG.info(pattern, vararg Any?)` types every argument as `Any?`, so a line
 * whose placeholders and arguments have drifted apart compiles and prints
 * nonsense. One such line shipped — the permanent-escalation error, which read
 * *"stopped permanently after true attempt(s) … (answeringPlayers=1)"* — and
 * review slid past it because the correct branch sat directly beside it.
 *
 * A log-capture test only covers the call sites somebody remembered to write one
 * for, and the guard has to be re-remembered at every new site. Making the
 * mistake **not compile** covers all of them, including the ones not written yet.
 * Two earlier fixes did that by naming and typing the parameters of a wrapper
 * function; that fixed two instances. This is the fix at the class: a record whose
 * fields are typed so that no two neighbours are interchangeable, rendered by the
 * one function that also owns the pattern.
 *
 * ## Reading it
 *
 * [WorkloadRef] renders as `server=… node=…`, so the emitted lines are the same
 * shape an operator already greps for — the structure moved into the type, not
 * into the output.
 */
internal object LoopLogging

/**
 * The workload a line is about: which declared server, on which node.
 *
 * These two were adjacent `Any?` arguments at several call sites. They are
 * distinct types, so a typed record refuses the swap that a varargs call accepts
 * silently. Neither value is player data — [server] is a declared object's name
 * and [node] is a node name, never an address.
 */
internal data class WorkloadRef(
    val server: ResourceName,
    val node: NodeName,
) {
    override fun toString(): String = "server=$server node=$node"
}

/**
 * Whether a *completed* world save has been confirmed for the container being
 * acted on.
 *
 * A value class rather than a `Boolean` for one reason: it sits next to
 * [WorldDataHolding] in [ContainerStopRecord], and those two adjacent booleans
 * are the swap that costs a world. A line reporting a save that never happened as
 * confirmed is the first thing an investigator reads after a world is lost, and
 * it would send them looking at the wrong component.
 */
@JvmInline
internal value class WorldSaveEvidence(
    val confirmed: Boolean,
)

/** Whether the container being stopped holds world data at all. See [WorldSaveEvidence]. */
@JvmInline
internal value class WorldDataHolding(
    val holdsWorld: Boolean,
)

/**
 * Everything the one container stop in this codebase reports about itself.
 *
 * Five loose scalars before, two of them adjacent `Boolean`s. Every field here
 * differs in type from its neighbours, so the arguments cannot be reordered
 * without the compiler objecting.
 */
internal data class ContainerStopRecord(
    val workload: WorkloadRef,
    val gracePeriod: Duration,
    val save: WorldSaveEvidence,
    val worldData: WorldDataHolding,
)

/**
 * The stop line.
 *
 * The pattern and the arguments are together in one place, so the only way to
 * change what is printed is to change this function — and every caller reaches it
 * through [ContainerStopRecord], which will not build with its fields out of
 * order.
 */
internal fun Logger.stoppingContainer(record: ContainerStopRecord) {
    info(
        "stopping {} gracePeriod={}s worldSaveConfirmed={} holdsWorldData={}",
        record.workload,
        record.gracePeriod.inWholeSeconds,
        record.save.confirmed,
        record.worldData.holdsWorld,
    )
}
