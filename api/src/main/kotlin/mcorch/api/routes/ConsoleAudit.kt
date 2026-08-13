package mcorch.api.routes

import mcorch.api.auth.Principal
import mcorch.schema.PaperServerDefinition
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * What was run, by whom, and what happened.
 *
 * `spec/04-output.md` §3. A console is the most consequential thing this API
 * offers, so every attempt is recorded — including the ones that were refused,
 * because "somebody tried to `stop` a production server" is exactly the line
 * worth having later.
 *
 * ## What it never records
 *
 * **The output.** That is where identities are densest, and nothing about
 * auditing needs it: the question an audit log answers is who did what and when.
 *
 * **The arguments, unless the server asked for them.** A command's arguments can
 * be a player name — `kick Alice` — and this log is the one sink guaranteed to be
 * written to disk and read months later. So by default it records the verb and
 * how many arguments there were, which answers "who kicked somebody" without
 * answering "who". `spec.console.auditCommandText: true` keeps the whole line,
 * and is an operator deliberately overriding the standing rule against logging
 * player names for one server.
 *
 * ## Structured logging, not a table
 *
 * It goes to the logger rather than the store. An audit trail in the same
 * database as the state is one a compromised orchestrator can edit, and log
 * shipping is a solved problem this project should not re-solve. If it ever needs
 * to be queryable, that is a store decision with retention attached, and this
 * interface is where it would be made.
 */
internal class ConsoleAudit {
    fun record(
        principal: Principal,
        definition: PaperServerDefinition,
        command: String,
        outcome: ConsoleRoutes.Outcome,
        clock: Clock,
    ) {
        val rendered = render(command, definition.spec.console.auditCommandText)
        LOG.info(
            "console identity={} server={} command={} tier={} outcome={} at={}",
            principal.name,
            definition.metadata.name.value,
            rendered,
            principal.tier.wireValue,
            outcome,
            clock.instant(),
        )
    }

    /**
     * The command, at the detail this server declared.
     *
     * The default form deliberately keeps the argument *count*: "kick, 1 argument"
     * and "kick, 0 arguments" are different events, and dropping the count would
     * make a malformed attempt indistinguishable from a real one.
     */
    private fun render(
        command: String,
        full: Boolean,
    ): String {
        if (full) return command
        val parts = command.trim().split(Regex("\\s+"))
        val verb = parts.firstOrNull().orEmpty()
        val arguments = (parts.size - 1).coerceAtLeast(0)
        return "$verb (+$arguments args)"
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger("mcorch.audit.console")
    }
}
