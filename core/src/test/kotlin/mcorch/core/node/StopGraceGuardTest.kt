package mcorch.core.node

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.StopGrace
import mcorch.core.StopGraceCeiling
import mcorch.core.WorkloadHandle
import mcorch.core.coreTest
import mcorch.core.testing.KotlinSource
import mcorch.cri.ContainerFilter
import mcorch.cri.ContainerId
import mcorch.cri.ContainerSpec
import mcorch.cri.ContainerStatus
import mcorch.cri.ContainerSummary
import mcorch.cri.CriClient
import mcorch.cri.CriTimeouts
import mcorch.cri.ExecResult
import mcorch.cri.ExecStreams
import mcorch.cri.ImageId
import mcorch.cri.ImageInfo
import mcorch.cri.ImageName
import mcorch.cri.RegistryAuth
import mcorch.cri.RuntimeStatus
import mcorch.cri.RuntimeVersion
import mcorch.cri.SandboxFilter
import mcorch.cri.SandboxId
import mcorch.cri.SandboxSpec
import mcorch.cri.SandboxStatus
import mcorch.cri.SandboxSummary
import mcorch.cri.StopGracePeriod
import mcorch.schema.NodeName
import mcorch.schema.PaperServerDefaults
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.schema.SpecBounds
import mcorch.store.SecretStore
import mcorch.store.SecretValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What [LocalNode.stopWorkload] does with the grace period it is handed, before
 * anything reaches containerd — and what [StopGraceCeiling] does before that.
 *
 * This guard used to be a local `gracePeriod.isPositive()` check standing in
 * front of a [StopGracePeriod] that enforced strictly more, so the two disagreed
 * about which values were acceptable — and a value in the gap failed deep inside
 * the call instead, as an unclassified `RuntimeException` that the node's
 * catch-all turns into a *non-retryable* rejection. A permanent abort of a
 * drain, produced by an argument, with the container runtime never consulted.
 *
 * The wider gap was on the other side: a grace period large enough to overflow
 * containerd's own seconds-to-nanoseconds conversion passed both checks and was
 * sent, and containerd answers such a request by killing the container almost
 * immediately and reporting success. Bigger was not safer. So these assertions
 * are as much about **what never reaches the client** as about what the caller
 * sees.
 *
 * ## Two bounds, and only one of them is this node's
 *
 * Since the thirtieth audit the operational ceiling is carried by [StopGrace], the
 * type [LocalNode.stopWorkload] takes, so the node applies nothing of its own to it
 * — the tests below build the value the way the drain controller does and assert on
 * what reaches the client. **There is no mutation for "the node forgets the
 * ceiling"**, and the reason belongs here rather than in the harness: the node
 * cannot express forgetting it. It never sees an unbounded duration. What is left
 * at this end is containerd's own bound (`StopGracePeriod.of`), which is where a
 * second `Node` implementation is entitled to differ.
 */
class StopGraceGuardTest {
    /**
     * **Rewritten by the twenty-ninth audit's third finding, and the claim changed.**
     *
     * It used to assert that a value containerd's arithmetic would invert is
     * *refused*. It is now **capped** at [StopGraceCeiling.MAX] and the stop goes
     * out, so the refusal it asserted no longer happens. The property it existed for
     * is stronger than before rather than weaker: such a value cannot reach
     * containerd through this node at all, and now for a structural reason rather
     * than because one guard says no.
     *
     * Why the verdict changed is in [StopGraceCeiling]: the operation being refused
     * was the **stop**, and a stop nobody can issue is a populated, world-holding
     * server nobody can retire. The cap is safe because of where the stop sits in
     * the protocol — every path to it ends in `mayStop`, so a completed save is
     * already confirmed and the grace period is the last-resort net. There are
     * **two** such paths and `DrainWiringTest` is what holds that count; the
     * sentence this used to carry said "the zero-player gate followed by `mayStop`",
     * which `DrainController`'s class note has contradicted since the re-issue was
     * written.
     */
    @Test
    fun `a grace period containerd would invert is capped, not sent`(
        @TempDir root: Path,
    ) = coreTest {
        // One second over StopGracePeriod.MAX_SECONDS. Measured against
        // containerd 2.3.3: a request of this size is answered by killing the
        // container in under half a second, with an empty success response that
        // is indistinguishable from a stop which waited the whole period.
        val overflowing = (StopGracePeriod.MAX_SECONDS + 1).seconds
        val client = RefusingCriClient()

        node(client, root).stopWorkload(handle(), StopGrace.of(overflowing, NO_WORLD))

        // The whole point, unchanged: containerd was never asked to wait that
        // long. It was asked to wait the ceiling.
        client.stops shouldBe listOf(ContainerId("c1") to StopGracePeriod.ofSeconds(7200).getOrThrow())
        StopGraceCeiling.MAX shouldBe 2.hours
    }

    /**
     * The bound itself, called directly with the inputs no scenario can produce.
     *
     * A rule with call sites in one implementation is a rule a unit test has to be
     * able to reach; the node test above can only drive the one path it drives.
     */
    @Test
    fun `the ceiling caps a long finite grace period and leaves everything else alone`() {
        StopGraceCeiling.bound(30.seconds, NO_WORLD) shouldBe 30.seconds
        StopGraceCeiling.bound(StopGraceCeiling.MAX, NO_WORLD) shouldBe StopGraceCeiling.MAX
        StopGraceCeiling.bound(StopGraceCeiling.MAX + 1.seconds, NO_WORLD) shouldBe StopGraceCeiling.MAX
        StopGraceCeiling.bound(StopGracePeriod.MAX_SECONDS.seconds, NO_WORLD) shouldBe StopGraceCeiling.MAX
        // Not a duration anybody meant. Capping it would turn an argument the code
        // cannot interpret into a plausible-looking stop, so it is handed on
        // untouched to the rule that refuses it and says why.
        StopGraceCeiling.bound(Duration.INFINITE, NO_WORLD) shouldBe Duration.INFINITE
        StopGraceCeiling.bound(Duration.ZERO, NO_WORLD) shouldBe Duration.ZERO
        StopGraceCeiling.bound((-30).days, NO_WORLD) shouldBe (-30).days
    }

    /**
     * **The thirtieth audit's first finding: the ceiling may not invert the pair it
     * clamps half of.**
     *
     * `stopGracePeriod` and `drain.saveTimeout` are validated *together* —
     * `LifecycleSpec.init` refuses a `PaperServer` whose grace period does not exceed
     * its save timeout by [PaperServerDefaults.MIN_STOP_GRACE_MARGIN], because a
     * grace period shorter than the save timeout kills the container part-way
     * through the save. A row carrying `saveTimeout = 3h` and
     * `stopGracePeriod = 3h1m` satisfies that, decodes, and used to be stopped with
     * two hours: SIGKILL into Paper's shutdown save, which is a torn region file.
     *
     * The two conditions are correlated rather than independent, which is what makes
     * this reachable at all: the cap only fires on a definition that bypassed
     * `PaperServerReader`, and that is the same population that can carry a save
     * timeout above `PaperServerDefaults.MAX_TIMEOUT`.
     *
     * The relation is restated here from the public constant rather than called
     * through `SpecInvariants.stopGraceProblem`, which is `internal` to `:schema`. If
     * that rule ever changes shape this assertion has to be re-derived — the
     * constant is shared, the arithmetic around it is not.
     */
    @Test
    fun `a grace period is never capped below the save timeout it was validated against`() {
        val margin = PaperServerDefaults.MIN_STOP_GRACE_MARGIN
        val pairs =
            listOf(
                // The reported case, and the one the old ceiling inverted.
                3.hours to (3.hours + 1.minutes),
                // The smallest margin the schema accepts, well past the ceiling.
                5.hours to (5.hours + margin),
                // A save timeout under the ceiling: the floor is below MAX, so MAX
                // is what bites and nothing about the pair changes.
                3.minutes to 10.hours,
            )
        for ((saveTimeout, declared) in pairs) {
            withClue("saveTimeout=$saveTimeout declared=$declared") {
                // The premise: every pair here is one the schema would accept.
                declared shouldBeGreaterThanOrEqualTo saveTimeout + margin
                val effective = StopGraceCeiling.bound(declared, saveTimeout)
                effective shouldBeLessThanOrEqualTo declared
                effective shouldBeGreaterThanOrEqualTo saveTimeout + margin
            }
        }
        // …and the cap is still a cap. Without this the assertions above are
        // satisfied by a ceiling that does nothing at all.
        StopGraceCeiling.bound(10.hours, 3.minutes) shouldBe StopGraceCeiling.MAX
    }

    /**
     * **The thirty-first audit's first finding: where the floor makes the ceiling
     * inoperative, and it is not at the far end.**
     *
     * [StopGraceCeiling.ceilingFor] is `max(MAX, saveTimeout + margin)`, so the
     * moment the save timeout passes `MAX - margin` the ceiling stops being two hours
     * and becomes the save timeout — and from there it rises with it, all the way to
     * the runtime's own refusal 292 years out. `saveTimeout = 30d` beside
     * `stopGracePeriod = 31d` clears `LifecycleSpec.init`, decodes from a nanosecond
     * column, and is *capped* — to a month. A month is what containerd is asked to
     * wait, and that is what the assertion at the bottom reads.
     *
     * The trade is still the right way round and the floor stays: a parked worker
     * loses no world, an inverted pair loses one. What was wrong was the sentence.
     * The residual [StopGraceCeiling] named was the *refusal* at the top of the
     * range, reachable only when both halves are absurd — while this, the reachable
     * one, went unnamed. So it is pinned here, in both halves: the boundary where the
     * floor takes over, and what actually reaches the runtime past it.
     *
     * **The worker is not parked for a month, and that is `:cri`'s doing rather than
     * this ceiling's.** `GrpcCriClient` deadlines the call at
     * `min(gracePeriod, CriTimeouts.stopDeadlineCap) + deadlineSlack` — two hours by
     * default — while sending the whole grace period, so the value asserted below is
     * unchanged and the wait is bounded somewhere else. This test says nothing about
     * that deadline on purpose: it is measured in `:cri`, against a real containerd,
     * and asserting it here from a fake would be this module claiming a property it
     * cannot observe.
     *
     * **What it does have to point at, since the thirty-eighth audit.** The month
     * built below is far past `stopDeadlineCap + deadlineSlack` — the threshold past
     * which a re-issue can never reach the runtime's kill, which is the cap plus the
     * slack and not the cap — so this is the shape of the value that would park a
     * drain for ever, constructed here in a green test. It is legal to
     * construct and no definition the loop acts on produces one; what keeps that true
     * is a relation between three modules' constants, and it is asserted rather than
     * surveyed in `the ceiling a stop may carry stays inside the deadline that
     * finishes it`, below. Read the two together: this one says what the arithmetic
     * does with an out-of-population pair, that one says why no pass presents one.
     */
    @Test
    fun `above a two-hour save timeout the ceiling is the save timeout, and a month-long stop goes out`(
        @TempDir root: Path,
    ) = coreTest {
        val margin = PaperServerDefaults.MIN_STOP_GRACE_MARGIN

        // The boundary itself, from both sides. Below it the floor sits under MAX and
        // MAX is what bites; one second above it, the floor is what bites.
        StopGraceCeiling.ceilingFor(StopGraceCeiling.MAX - margin) shouldBe StopGraceCeiling.MAX
        StopGraceCeiling.ceilingFor(StopGraceCeiling.MAX - margin + 1.seconds) shouldBe
            StopGraceCeiling.MAX + 1.seconds

        // Past it there is no bound of this ceiling's own left: the reported case,
        // and one two orders of magnitude larger.
        StopGraceCeiling.bound(3.hours + 1.minutes, 3.hours) shouldBe 3.hours + margin
        StopGraceCeiling.bound(31.days, 30.days) shouldBe 30.days + margin

        // …and it is not arithmetic in a vacuum. This is what containerd is asked to
        // wait, and what `awaitStopped` measures the container against. What the call
        // is *deadlined* at is a separate number and a separate module's.
        val client = RefusingCriClient()
        node(client, root).stopWorkload(handle(), StopGrace.of(31.days, 30.days))

        client.stops
            .single()
            .second.seconds shouldBe (30.days + margin).inWholeSeconds
    }

    /**
     * **The thirty-eighth audit's first follow-up: the loop above this one
     * terminates on a relation between four constants in three modules, and nothing
     * was looking at it.**
     *
     * `DrainController.awaitStopped` re-issues a stop that did not take, with the
     * *same* grace period — `failure-modes.md` item 7, and deliberate. What makes a
     * re-issue finish anything was measured against containerd 2.3.3 in
     * `cri/src/integrationTest`: it does **not** re-deliver the stop signal (the
     * runtime compare-and-swaps a per-container flag the first time a stop with a
     * timeout sends one), so all it supplies is a fresh grace period on a fresh
     * transport deadline, and it reaches `SIGKILL` only when its own grace period
     * expires before its own deadline. `GrpcCriClient` sets that deadline to
     * `min(gracePeriod, CriTimeouts.stopDeadlineCap) + deadlineSlack`. Past
     * **`stopDeadlineCap + deadlineSlack`** the two are ordered the wrong way by
     * construction, so every re-issue ends exactly as the first did, on every pass,
     * for ever. No retry count reaches that — it is the inequality. Note the
     * threshold is the cap *plus the slack*: inside that band the grace period is
     * still what expires and the kill is still reached, which is `:cri`'s own
     * wording. The assertions below are stated at the bare cap, one slack short,
     * deliberately — the slack is a margin `:cri` may retune and this relation
     * should not be spending it. It is not confined to the re-issue either:
     * `DrainController.stop` calls with the same value, times out on the same
     * inequality and aborts as *retryable*, so the next pass comes back into the same
     * call. Both stop sites spin, and each is behaving correctly in isolation.
     *
     * So the property is *"nothing a `Node` can be handed exceeds the deadline `:cri`
     * will wait for it"*, and it currently holds at **equality against a strict
     * `>`**: one second of movement in either outer constant makes it live. `:cri`'s
     * own KDoc says in as many words that it cannot see `:schema`'s cap and
     * deliberately does not depend on it, which is exactly the borrowed-constant
     * drift `SpecBounds.init` was written for one module down. `:core` depends on
     * both and is the only place the two are visible together.
     *
     * ## Why the assertions are these three
     *
     * The first is the relation as stated. The second is the one it is not enough on
     * its own: [StopGraceCeiling.ceilingFor] has a **floor**, so past a save timeout
     * of `MAX - margin` the effective ceiling is the save timeout and rises with it —
     * which puts a fourth constant, `SpecBounds.MAX_SAVE_TIMEOUT`, inside the
     * relation. The third is **logically implied by the second** —
     * `min(r, ceilingFor(s)) <= ceilingFor(s)` for any `r` — and is not extra
     * coverage of the arithmetic. What it adds is the *path*: it runs the real
     * [StopGrace.of], so it is the assertion that goes red if `of` ever stops
     * routing through [StopGraceCeiling.bound], which is the one way the first two
     * could both hold while what reaches a node does not.
     *
     * The one value that escapes that argument is a non-finite request, which
     * `SpecBounds.capStop` and [StopGraceCeiling.bound] both pass through untouched —
     * and it is covered rather than an exception: `StopGracePeriod.of` refuses it
     * before any stop leaves this process, so it never reaches `stopContainer` and
     * can never be what a deadline is capped below. That is the subject of `an
     * unbounded grace period is refused by the rule that owns it, not by the
     * catch-all`, above.
     *
     * ## Why this is a test, given that a `require` also exists
     *
     * The check would naturally have gone in [StopGraceCeiling]'s `init`, the way
     * `SpecBounds.init` binds its own two borrowed constants. The far side is a
     * `:cri` type and [StopGraceCeiling] lives in `Node.kt`, which is the
     * distribution seam: `:cri` is an `implementation` dependency precisely so that
     * `LocalNode` is the only class in `:core` naming a CRI type, and putting one in
     * the interface's own file would make the seam's policy ceiling a statement about
     * one runtime's transport configuration.
     *
     * So the relation is held in two places and **this is not the only one**.
     * `LocalNode.open` carries a `require` against the cap its own client is built
     * with, which is the deployment half; see
     * `opening a node runs the stop deadline pre-flight…` below. This is the constant
     * half: it runs on every build with no node constructed, and it names which of
     * the four constants moved. A `require` cannot do that job — it only fires where
     * a node is opened — and a test cannot do the other one, because it cannot see a
     * config a future `LocalNodeConfig` might supply.
     *
     * This says nothing about what containerd then does, which is `:cri`'s to measure
     * and is measured — `StopDeadlineCapIT`. What is asserted here is arithmetic on
     * constants, which is the whole of what was missing.
     *
     * ## What the red-proof found, because one result reads the wrong way
     *
     * Lowering `CriTimeouts.stopDeadlineCap` to an hour reddened **this test and
     * nothing else** in 954 — the far-side constant was pinned by nothing at all.
     * That measurement predates `LocalNode.open`'s `require`; the same mutation now
     * reddens this *and* the pre-flight test, which is what the record further down
     * says. The suite total is the tell that two records were taken at different
     * times, and it is the reason a red set is written with the count it was measured
     * against.
     * Raising `PaperServerDefaults.MAX_STOP_GRACE_PERIOD` to three hours reddened
     * this and two others, which looks like coverage and is not: both of those
     * (`a grace period containerd would invert is capped, not sent` here, and
     * `BoundedDeadlineTest`) assert the constant's *value*, so somebody deliberately
     * raising the ceiling updates them as part of the change and learns nothing.
     * This one states a relation they cannot satisfy by being edited.
     *
     * The mutation for the second assertion has to decouple `SpecBounds`' two borrows
     * — grace ceiling to three hours, save ceiling to two — because while both come
     * from `PaperServerDefaults`, `SpecBounds.init` already forbids the pair that
     * would break it. Under that mutation the first assertion stays green and the
     * second fails, which is what says it is carrying its own weight rather than
     * restating the first.
     */
    @Test
    fun `the ceiling a stop may carry stays inside the deadline that finishes it`() {
        // The shipped configuration. `the shipped node runs on the default CRI
        // timeouts` is what makes reading the default honest.
        val cap = CriTimeouts().stopDeadlineCap

        withClue(
            "StopGraceCeiling.MAX is above the deadline :cri will wait for a stop, so a stop carrying it can " +
                "never reach the runtime's kill and DrainController.awaitStopped re-issues it for ever. Raise " +
                "CriTimeouts.stopDeadlineCap with it, or lower PaperServerDefaults.MAX_STOP_GRACE_PERIOD, which " +
                "is where this is borrowed from",
        ) {
            StopGraceCeiling.MAX shouldBeLessThanOrEqualTo cap
        }

        withClue(
            "the ceiling's floor raises it above the deadline for the widest save timeout a stored definition " +
                "can carry, so the relation above is not enough on its own: SpecBounds.MAX_SAVE_TIMEOUT plus " +
                "PaperServerDefaults.MIN_STOP_GRACE_MARGIN has to stay inside CriTimeouts.stopDeadlineCap too",
        ) {
            StopGraceCeiling.ceilingFor(SpecBounds.MAX_SAVE_TIMEOUT) shouldBeLessThanOrEqualTo cap
        }

        withClue(
            "the extreme pair SpecBounds admits builds a StopGrace above the deadline. Implied by the assertion " +
                "above unless StopGrace.of has stopped routing through StopGraceCeiling.bound — check that " +
                "first, it is the only way this fails on its own",
        ) {
            StopGrace
                .of(SpecBounds.MAX_STOP_GRACE_PERIOD, SpecBounds.MAX_SAVE_TIMEOUT)
                .period shouldBeLessThanOrEqualTo cap
        }
    }

    /**
     * The pre-flight in `LocalNode.open` runs, and passes on the shipped constants.
     *
     * The `require` it asserts is the thirty-ninth audit's answer to my having
     * demoted this check to a test: `LocalNode` is the one class `:core` permits to
     * name CRI types and the one holding the [mcorch.cri.CriClientConfig] it just
     * built, so it can bind the relation to the cap the process **actually runs on**
     * rather than to `CriTimeouts()`. The arithmetic test above pins the constants;
     * this pins the deployment.
     *
     * **It cannot be driven to its failure from a test**, and that is worth saying
     * rather than leaving as a gap. `open` builds the config itself, so no argument
     * reaches the cap — the only way to falsify the relation is to move one of the
     * constants, which is a source mutation and not an input. So this test is a
     * *reachability* proof: it says the `require` is evaluated on the ordinary wiring
     * path and does not spuriously refuse. Lowering `CriTimeouts.stopDeadlineCap` to
     * an hour reddens exactly this and the arithmetic test, and this one's failure is
     * the `require`'s own message — that pairing is the red-proof of both halves.
     *
     * `open` does not connect eagerly, so no containerd is needed and the endpoint
     * string is never dialled.
     */
    @Test
    fun `opening a node runs the stop deadline pre-flight and passes on the shipped constants`(
        @TempDir root: Path,
    ) {
        val config =
            LocalNodeConfig(
                name = NODE,
                runtimeEndpoint = "unix:///run/containerd/containerd.sock",
                volumeRoot = root.resolve("volumes").createDirectories(),
                logRoot = root.resolve("logs").createDirectories(),
                assetRoot = root.resolve("assets").createDirectories(),
            )

        LocalNode.open(config, UnusedSecretStore).use { node ->
            node.name shouldBe NODE
        }
    }

    /**
     * A review trigger over who may speak about the CRI stop deadline in `:core`.
     * **It is not what makes the relation hold** — `LocalNode.open`'s `require` is,
     * and it binds the config the process is actually built with. This exists so the
     * *arithmetic* test above, which reads `CriTimeouts()`, keeps standing for the
     * shipped value, and so a second place growing an opinion about the cap is read
     * rather than merged.
     *
     * ## What it looks for, after the thirty-ninth audit widened it
     *
     * The first version banned the token `CriTimeouts` outright, and that had a hole
     * the audit named: both `CriClientConfig` and `CriTimeouts` are data classes, so
     * `cfg.copy(timeouts = cfg.timeouts.copy(stopDeadlineCap = 1.hours))` configures
     * the cap and contains no `CriTimeouts` token at all. Scan green, relation
     * asserted against a default nobody runs. `stopDeadlineCap` is in the token set
     * now, which catches that shape at both of its halves.
     *
     * Widening it that far means the `require` — which reads the field on purpose —
     * is itself a hit, so this cannot be a ban any more and is a **classification**:
     * a file that names either token is either one that builds a [CriClientConfig],
     * which is a `Node` implementation wiring its own transport and is entitled to an
     * opinion about its own deadline, or it is a finding. A list of permitted *paths*
     * was the alternative and is worse — the next `Node` implementation would have to
     * be edited past it, which is the seam this project protects.
     *
     * **`LocalNode.kt` is therefore permanently exempt, and what covers it is not
     * this test.** It names the field in code, so it is in the wiring class for good,
     * and a `copy(timeouts = …)` *there* is invisible here. Nor is it covered merely
     * because a `require` sits in the file: the property is an **identity** — the
     * `require` reads `criConfig.timeouts.stopDeadlineCap` and `CriClient.connect` is
     * handed that same `criConfig` value, so the number checked is the number
     * connected. `connect(criConfig.copy(timeouts = …))` would satisfy the `require`
     * and run on a different cap, and nothing mechanical would notice. That identity
     * is stated at the `require` itself, which is where somebody editing those two
     * lines will be looking.
     *
     * Prose is exempt: `Node.kt` names the constant in several KDoc paragraphs on
     * purpose. That is what the code/comment split in [mainSources] is for, and it is
     * what makes the vacuity control below load-bearing rather than decoration.
     *
     * ## Red-proof
     *
     * Three mutations, each reddening this and nothing else in 955. The first gave
     * `LocalNode.open` a `timeouts = mcorch.cri.CriTimeouts()`; note the spelling,
     * which carries no `import`, so a scan keyed on the import line would have been
     * green on it. The second was the audit's `copy` shape in a file that builds no
     * client. The third is the one that matters, because **the old scan would have
     * been green on it**: a helper taking a `CriClientConfig` and returning
     * `cfg.copy(timeouts = cfg.timeouts.copy(stopDeadlineCap = …))`, which names
     * `CriTimeouts` nowhere and `CriClientConfig(` nowhere — only the field. That is
     * the hole, and the field being in the token set is what closes it.
     *
     * A fourth attempt is worth recording as a *method* note. It configured the cap
     * to `Duration.ZERO`, which `CriTimeouts.init` rejects — so [StopGraceCeiling]'s
     * class-init threw and **76** tests went red, this one among them. A run like
     * that attributes nothing: the assertion under test cannot be distinguished from
     * collateral. Give a mutation a value the constructors accept, or it is measuring
     * the constructor.
     */
    @Test
    fun `nothing but a node's own wiring speaks about the CRI stop deadline`() {
        val sources = mainSources()

        withClue("expected to run with the :core module directory as the working directory") {
            sources.shouldNotBeEmpty()
        }

        // The control, and it has to be the *classifier's* subject rather than any
        // findable token: if nothing builds a CriClientConfig in code, every file is
        // trivially "not wiring" and the partition below says nothing.
        val wiring = sources.filter { (_, code) -> code.any { "CriClientConfig(" in it } }.map { it.first }
        withClue("no CriClientConfig( in :core's code — this scan is not reading the file the claim is about") {
            wiring.shouldNotBeEmpty()
        }

        val speaking =
            sources
                .filter { (_, code) -> code.any { "CriTimeouts" in it || "stopDeadlineCap" in it } }
                .map { it.first }

        withClue(
            "these files name the CRI stop deadline without building the client it belongs to, so CriTimeouts() " +
                "may no longer be the cap the shipped node runs on. Re-derive `the ceiling a stop may carry " +
                "stays inside the deadline that finishes it` against the value actually configured, and check " +
                "LocalNode.open's require still binds it: ${speaking - wiring.toSet()}",
        ) {
            (speaking - wiring.toSet()).shouldBeEmpty()
        }
    }

    /**
     * The residual named in [StopGraceCeiling], pinned so it is a decision rather
     * than a discovery.
     *
     * A save timeout large enough that the derived floor passes what *containerd*
     * accepts leaves the stop refused rather than capped — the cap-versus-refuse
     * trade pointing the other way, and deliberately so: a refusal is recorded and
     * loud where a cap that inverts the pair is silent and costs a world. It needs
     * both halves of the pair to be absurd (292 years), not merely unvalidated.
     */
    @Test
    fun `a save timeout past the runtime's own bound leaves the stop refused, not silently inverted`(
        @TempDir root: Path,
    ) = coreTest {
        val absurd = (StopGracePeriod.MAX_SECONDS + 1).seconds
        val client = RefusingCriClient()

        // The floor raises the ceiling above what the runtime will take, so nothing
        // caps it and the runtime's own rule is what answers.
        StopGraceCeiling.bound(absurd, absurd) shouldBe absurd
        shouldThrow<NodeException.Rejected> {
            node(client, root).stopWorkload(handle(), StopGrace.of(absurd, absurd))
        }
        client.stops.shouldBeEmpty()
    }

    @Test
    fun `an unbounded grace period is refused by the rule that owns it, not by the catch-all`(
        @TempDir root: Path,
    ) = coreTest {
        // Duration.INFINITE cleared the node's own guard and failed inside the
        // CRI call instead. Same verdict either way, but reached by the rule
        // that owns it, and with a message that says which rule.
        val client = RefusingCriClient()

        val thrown =
            shouldThrow<NodeException.Rejected> {
                node(client, root).stopWorkload(handle(), StopGrace.of(Duration.INFINITE, NO_WORLD))
            }

        thrown.operation shouldBe NodeOperation.STOP
        thrown.message.shouldNotBeNull() shouldContain "finite"
        thrown.message.shouldNotBeNull() shouldNotContain "does not classify"
        client.stops.shouldBeEmpty()
    }

    @Test
    fun `zero and negative are still refused`(
        @TempDir root: Path,
    ) = coreTest {
        for (bad in listOf(Duration.ZERO, (-1).seconds, (-30).days)) {
            val client = RefusingCriClient()
            val node = node(client, root)
            shouldThrow<NodeException.Rejected> { node.stopWorkload(handle(), StopGrace.of(bad, NO_WORLD)) }
            client.stops.shouldBeEmpty()
        }
    }

    @Test
    fun `a long but legal grace period is passed through whole and unshortened`(
        @TempDir root: Path,
    ) = coreTest {
        // Two hours is the schema's cap for a Paper server, and it is nowhere
        // near the runtime's limit — the guard must not be mistaken for a policy
        // on how long a save may take.
        val client = RefusingCriClient()
        node(client, root).stopWorkload(handle(), StopGrace.of(2.hours, 3.minutes))

        client.stops shouldBe listOf(ContainerId("c1") to StopGracePeriod.ofSeconds(7200).getOrThrow())
    }

    /**
     * Also rewritten by the twenty-ninth audit's third finding — see the note on
     * `a grace period containerd would invert is capped, not sent`.
     *
     * The largest value the *runtime* honours is 292 years, and the reason it may not
     * be sent is not containerd's. It is that no reader in this system accepts more
     * than two hours, so a grace period above that came from a row nobody validated,
     * and what the container is given is not a number anybody chose.
     *
     * **The justification used to be the call's deadline** — `gracePeriod + slack`,
     * so a 292-year grace was a worker parked with no effective timeout. That is no
     * longer this bound's to claim: `GrpcCriClient` deadlines a stop at
     * `min(gracePeriod, CriTimeouts.stopDeadlineCap) + deadlineSlack`, so the wait is
     * bounded whatever is sent. What is *still* this bound's is what the runtime is
     * asked to wait, and the drain reads that number back — `awaitStopped` measures a
     * container against the period the runtime was given, so a grace period nobody
     * chose is a container that is never overdue.
     */
    @Test
    fun `the largest grace period the runtime honours is still capped to the widest a reader accepts`(
        @TempDir root: Path,
    ) = coreTest {
        val client = RefusingCriClient()
        node(client, root).stopWorkload(handle(), StopGrace.of(StopGracePeriod.MAX_SECONDS.seconds, NO_WORLD))

        client.stops
            .single()
            .second.seconds shouldBe StopGraceCeiling.MAX.inWholeSeconds
    }

    @Test
    fun `a workload with no container is a no-op, and is still argument-checked first`(
        @TempDir root: Path,
    ) = coreTest {
        val sandboxOnly = WorkloadHandle(NODE, "s1", containerId = null)

        val quiet = RefusingCriClient()
        node(quiet, root).stopWorkload(sandboxOnly, StopGrace.of(30.seconds, NO_WORLD))
        quiet.stops.shouldBeEmpty()

        // A nonsense grace period is refused whether or not there is anything to
        // stop. The argument is wrong either way, and a caller that only learns
        // so once a container exists learns so during a drain.
        val refused = RefusingCriClient()
        shouldThrow<NodeException.Rejected> {
            node(refused, root).stopWorkload(sandboxOnly, StopGrace.of(Duration.INFINITE, NO_WORLD))
        }
        refused.stops.shouldBeEmpty()
    }

    private fun node(
        client: CriClient,
        root: Path,
    ): LocalNode =
        LocalNode(
            name = NODE,
            client = client,
            secrets = UnusedSecretStore,
            volumeRoot = root.resolve("volumes").createDirectories(),
            logRoot = root.resolve("logs").createDirectories(),
            assetRoot = root.resolve("assets").createDirectories(),
            sandboxNamespace = "mcorch-test",
            cgroupParent = null,
        )

    private fun handle(): WorkloadHandle = WorkloadHandle(NODE, "s1", "c1")

    /**
     * Every `.kt` under this module's main sources as `path to its code lines`.
     *
     * Comments are dropped and string literals blanked before anything is looked
     * for, because the tokens scanned here are written on purpose in the KDoc of
     * `Node.kt` and `LocalNode.kt` — a scan that could not tell those from an import
     * would be red on the day it was written and would stay red by being weakened.
     * No count of those paragraphs is given: the first draft of this note carried
     * one and it was stale by the end of the same commit, which is the failure
     * `DrainController`'s own class note warns about.
     *
     * The stripping, the block-comment nesting and the fail-open check all live in
     * [KotlinSource], shared with `:app`'s startup-channel scan rather than copied —
     * its KDoc carries the reasoning, including which unmatched-opener spelling is
     * loud and which is silent.
     */
    private fun mainSources(): List<Pair<String, List<String>>> = KotlinSource.tree("src/main/kotlin")

    private companion object {
        val NODE: NodeName = NodeName.of("test-node").getOrThrow()

        /**
         * The save timeout of a workload with no world, which is what
         * `DrainSubject.saveTimeout` answers for one. It puts no floor under the
         * ceiling, so every assertion that is about the *cap* uses it rather than
         * quietly relying on a floor it does not name.
         */
        val NO_WORLD: Duration = Duration.ZERO
    }
}

/**
 * A CRI client that records stops and refuses everything else.
 *
 * Every other member throws rather than returning a benign default: a stop guard
 * test that quietly let another call through would be asserting less than it
 * appears to.
 */
private class RefusingCriClient : CriClient {
    val stops: MutableList<Pair<ContainerId, StopGracePeriod>> = mutableListOf()

    override suspend fun stopContainer(
        id: ContainerId,
        gracePeriod: StopGracePeriod,
    ) {
        stops += id to gracePeriod
    }

    override suspend fun version(): RuntimeVersion = unexpected("version")

    override suspend fun status(): RuntimeStatus = unexpected("status")

    override suspend fun imageStatus(image: ImageName): ImageInfo? = unexpected("imageStatus")

    override suspend fun listImages(image: ImageName?): List<ImageInfo> = unexpected("listImages")

    override suspend fun pullImage(
        image: ImageName,
        auth: RegistryAuth?,
        sandbox: SandboxSpec?,
    ): ImageId = unexpected("pullImage")

    override suspend fun removeImage(image: ImageName): Unit = unexpected("removeImage")

    override suspend fun runSandbox(spec: SandboxSpec): SandboxId = unexpected("runSandbox")

    override suspend fun stopSandbox(id: SandboxId): Unit = unexpected("stopSandbox")

    override suspend fun removeSandbox(id: SandboxId): Unit = unexpected("removeSandbox")

    override suspend fun sandboxStatus(id: SandboxId): SandboxStatus = unexpected("sandboxStatus")

    override suspend fun listSandboxes(filter: SandboxFilter): List<SandboxSummary> = unexpected("listSandboxes")

    override suspend fun createContainer(
        sandboxId: SandboxId,
        sandboxSpec: SandboxSpec,
        spec: ContainerSpec,
    ): ContainerId = unexpected("createContainer")

    override suspend fun startContainer(id: ContainerId): Unit = unexpected("startContainer")

    override suspend fun removeContainer(id: ContainerId): Unit = unexpected("removeContainer")

    override suspend fun containerStatus(id: ContainerId): ContainerStatus = unexpected("containerStatus")

    override suspend fun listContainers(filter: ContainerFilter): List<ContainerSummary> = unexpected("listContainers")

    override suspend fun execSync(
        id: ContainerId,
        command: List<String>,
        timeout: Duration,
    ): ExecResult = unexpected("execSync")

    override suspend fun execStreamUrl(
        id: ContainerId,
        command: List<String>,
        streams: ExecStreams,
    ): String = unexpected("execStreamUrl")

    override suspend fun shutdown(gracePeriod: Duration) = Unit

    override fun close() = Unit

    private fun unexpected(operation: String): Nothing =
        error("the stop guard test reached $operation; nothing but stopContainer should be called")
}

private object UnusedSecretStore : SecretStore {
    override suspend fun put(
        ref: SecretRef,
        value: SecretValue,
    ): Unit = error("the stop path resolves no secrets")

    override suspend fun resolve(ref: SecretRef): SecretValue? = error("the stop path resolves no secrets")

    override suspend fun contains(ref: SecretRef): Boolean = error("the stop path resolves no secrets")

    override suspend fun removeKey(ref: SecretRef): Boolean = error("the stop path resolves no secrets")

    override suspend fun removeSecret(name: ResourceName): Int = error("the stop path resolves no secrets")

    override suspend fun listNames(): List<ResourceName> = error("the stop path resolves no secrets")

    override suspend fun listKeys(name: ResourceName): List<String> = error("the stop path resolves no secrets")

    override fun close() = Unit
}
