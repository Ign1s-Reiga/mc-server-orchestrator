package mcorch.core.node

/**
 * How much of a runtime's account of a failure may be carried into a
 * [mcorch.core.NodeException] — and therefore into `FailureStatus.message`,
 * which is **written to SQLite and served through the API**.
 *
 * ## Why this is stricter than a log line
 *
 * `:cri` withholds the same descriptions from what it logs, for the same
 * reason: a runtime's error text is free-form, and Go's
 * `fmt.Errorf("...: %+v", config)` habit means a rejected request can come back
 * with the request rendered into it. That is a promise about a third party's
 * strings, which is not a promise anyone can make.
 *
 * The exposure here is worse than the log's and needs the same answer applied
 * harder. A log line is a one-shot write into a stream an operator controls and
 * can rotate away. This string is **persisted** in `state.db` and **served** to
 * anything that reads observed status, so a secret that reaches it is at rest
 * and on the wire rather than merely printed once.
 *
 * ## One list, and it does not live here
 *
 * Which operations carry secret material is decided by
 * `mcorch.cri.CriOperation.requestMayCarrySecrets` and consulted through
 * [requestMayCarrySecrets] below. **Do not add a second list to this module.**
 * A security list that drifts between two modules is worse than no list: the
 * two would disagree silently and the one that mattered would be whichever was
 * consulted last. `:cri` keeps it as an exhaustive `when` rather than a set, so
 * a new RPC does not compile until somebody decides which side it falls on —
 * routing this module's decision through the same property is what extends that
 * fail-closed property across the boundary.
 *
 * This function takes the answer as a plain boolean so the *rendering* can be
 * tested from this module without naming a `mcorch.cri` type (CLAUDE.md
 * invariant 7). What cannot be tested from here is the one-token pass-through in
 * [LocalNode] that supplies it.
 *
 * ## What is deliberately kept
 *
 * `EXEC_SYNC` and `EXEC` are not on the list and their descriptions survive
 * whole. No call site puts a credential in an argv — the RCON password reaches
 * `rcon-cli` through the container's environment — and these are the single most
 * useful diagnostic the client produces. `failed to exec in container: timeout
 * 10s exceeded` is what separates a slow command on a healthy node from a node
 * that has stopped answering, and losing it costs exactly the diagnosis that
 * took two runs to reach. Withhold them the day a call site passes a secret as
 * an argument, and not before.
 *
 * @param operation the runtime operation that failed, for the record.
 * @param code the status code the runtime replied with, for the record.
 * @param rendered the full detail, description included, used when it is safe.
 * @param requestMayCarrySecrets whether this operation's *request* carries
 *   secret material — never re-derived here, always the runtime client's answer.
 */
internal fun runtimeDetail(
    operation: String,
    code: String,
    rendered: String,
    requestMayCarrySecrets: Boolean,
): String =
    if (requestMayCarrySecrets) {
        // The operation and the code are ours and are worth keeping: together
        // with the node and the classification that `NodeException` prepends,
        // they say what failed and how without quoting anybody. Only the
        // runtime's free-form sentence is dropped.
        //
        // Truncating instead would not do. Go error wrapping renders the
        // rejected request from the front, so a prefix of a failed
        // `CreateContainer` is a prefix of the container's environment.
        "$operation failed ($code); the runtime's own description is not recorded. This request carries " +
            "secret material, and a runtime that quotes the request it rejected would put that material in " +
            "the store and in the API. The detail is in the runtime's log on that node"
    } else {
        rendered
    }
