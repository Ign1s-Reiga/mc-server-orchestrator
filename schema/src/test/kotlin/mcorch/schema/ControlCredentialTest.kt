package mcorch.schema

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The merge rule and the derived flag, asserted on the type rather than through a
 * reconcile pass.
 *
 * Both are consumed in two modules — the routing sweep and the proxy drain refine
 * a verdict, the condition and the renderers read the flag — and a rule that lives
 * in one place because two callers would otherwise write it twice has to be
 * testable in that one place.
 */
class ControlCredentialTest {
    private fun control(
        reachable: Boolean = true,
        compatible: Boolean = true,
        credential: ControlCredential = ControlCredential.UNTESTED,
    ): ControlEndpointStatus =
        ControlEndpointStatus(
            reachable = reachable,
            pluginApiVersion = "1",
            compatible = compatible,
            lastContactAt = Instant.parse("2026-08-08T10:00:00Z"),
            credential = credential,
        )

    /**
     * `UNTESTED` is *no evidence*, so it never overwrites evidence.
     *
     * This is the whole rule, and the direction that matters is the second row: a
     * call that established nothing — an endpoint that stopped answering between
     * two calls of one pass, a refusal carrying some other code — must not be
     * allowed to erase a refusal. That erasure, written at the pass level rather
     * than here, is what made a refused proxy read healthy the moment a second
     * thing broke.
     */
    @Test
    fun `a verdict that established nothing leaves the previous one alone`() {
        for (held in ControlCredential.entries) {
            held.refinedBy(ControlCredential.UNTESTED) shouldBe held
        }
        ControlCredential.REJECTED.refinedBy(ControlCredential.ACCEPTED) shouldBe ControlCredential.ACCEPTED
        ControlCredential.ACCEPTED.refinedBy(ControlCredential.REJECTED) shouldBe ControlCredential.REJECTED
        ControlCredential.UNTESTED.refinedBy(ControlCredential.REJECTED) shouldBe ControlCredential.REJECTED
    }

    /**
     * The flag goes false only where something was observed to fail.
     *
     * An untested credential counts as *not refused* rather than as *not
     * accepted*, which is right for a badge and wrong for a gate — see the KDoc,
     * and `ControlCredentialWiringTest` for the enforcement that keeps the
     * consumer list to the two that may take the lenient reading.
     */
    @Test
    fun `usable is false only on a fact that was established`() {
        control(credential = ControlCredential.ACCEPTED).usable shouldBe true
        control(credential = ControlCredential.UNTESTED).usable shouldBe true
        control(credential = ControlCredential.REJECTED).usable shouldBe false
        control(reachable = false, credential = ControlCredential.ACCEPTED).usable shouldBe false
        control(compatible = false, credential = ControlCredential.ACCEPTED).usable shouldBe false
    }

    /**
     * The derived flag stays out of `equals`.
     *
     * The reconcile loop's write-skip is a structural comparison of statuses, so a
     * derived property that entered equality could make two records that describe
     * the same observation compare unequal — a store write per pass, on a type
     * that is written for every proxy in the fleet.
     */
    @Test
    fun `two records with the same fields are equal whatever the derived flag says`() {
        val rejected = control(credential = ControlCredential.REJECTED)
        rejected shouldBe control(credential = ControlCredential.REJECTED)
        rejected.usable shouldBe false
        (rejected == control(credential = ControlCredential.ACCEPTED)) shouldBe false
    }
}
