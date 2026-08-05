#!/usr/bin/env bash
# Red-proof for the drain's wiring assertions.
#
# `core/src/test/kotlin/mcorch/core/DrainWiringTest.kt` asserts the *shape* of
# DrainController's safety claims — that each rule is applied unconditionally, that
# the value it produces is the value that leaves, and that every call which ends a
# container is decided in one file. `SaveEvidenceTest` asserts what the rules
# themselves do, and `DrainTest` asserts the one stop gate a scenario can reach. A
# structural test cannot be sabotaged behaviourally, so its red-proof has to
# sabotage the wiring, and one sabotage is not enough: these assertions fail
# independently of each other, and four of the mutations below restored round 18's
# critical while the whole suite, DrainWiringTest included, stayed green when the
# twentieth audit found them.
#
# Each mutation is applied to a working copy of one source file, the test class
# that must catch it is run, and the file is restored — mutated source is never
# committed and never survives this script, including on failure or interrupt.
#
#   D1..D4   the wiring defects the twentieth audit demonstrated. DrainWiringTest.
#   D5       the same narrowing written where the fix moved the predicate to. It is
#            behaviour now, so SaveEvidenceTest is what has to catch it — a remedy
#            that only relocates a defect has to be traced to its new home.
#   D6       a stop added outside DrainController, which is where a drain audit
#            looks and where a file-scoped scan cannot see.
#   D7       a stop that keeps its place and loses its gate.
#   D8       the adoption applied to the wrong drain — the second address the same
#            extraction created, and only expressible since it became a function.
#   D9       the record-level rule narrowed inside itself.
#   D10      the stop gate *narrowed* rather than deleted: it keeps the token, the
#            count and the enclosing function, so no structural assertion can see
#            it. The twenty-first audit's finding, and the reason DrainTest now
#            carries that branch.
#   D11..D12 the single-exit precondition: `advanceOnce` private, with one caller.
#   D13      a stop reached through a same-named wrapper, which used to move the
#            whole file onto the "performs it" side of the classifier.
#   D14      a *removal* decided outside Reconciler. `removeWorkload` ends a
#            container too, and the scan was keyed on `stopWorkload` alone.
#   C1..C3   controls: the rule deleted outright, once per assertion arm. If these
#            do not redden, the harness is not reaching the assertions at all.
#   S1       the self-test. See below.
#
# The verdict is read from the JUnit XML, never from Gradle's exit status: a
# mutation that fails to compile also exits non-zero, and calling that "caught"
# would be the same false green in a different costume. No XML is an unknown.
#
# **And it is read per test case, not per class.** "Something in DrainWiringTest
# went red" is not the claim — the claim is that the assertion written for this
# mutation is what bit. Rename `advance` and `rangeOf`'s exactly-one-declaration
# check throws in two tests, so the class reddens for every mutation and both
# controls at once, and a run that proves nothing at all reports "all caught". The
# twenty-first audit reproduced that, having previously signed off on a 10/10 run
# from this script as independent verification. So each mutation carries the names
# it must redden, and the red set has to match exactly: a name missing means the
# assertion did not bite, a name extra means something else broke and the run says
# nothing about either. `S1` is the proof that this can fail — it breaks the whole
# class the way a rename would and requires the verdict to refuse the result.
#
# A mutation that no longer applies is a failure too, and a deliberate one: it
# means the source it was written against has moved, and whether the defect it
# describes is still expressible has to be decided by a human rather than assumed.
#
#   ./scripts/dev/drain-wiring-mutations.sh          run all of them, and S1
#   ./scripts/dev/drain-wiring-mutations.sh D3 C1    run some
#   ./scripts/dev/drain-wiring-mutations.sh S1       the self-test alone

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
RESULTS="$REPO_ROOT/core/build/test-results/test"
BACKUP_DIR="$(mktemp -d -t drain-wiring-XXXXXX)"

CONTROLLER=core/src/main/kotlin/mcorch/core/DrainController.kt
RECONCILER=core/src/main/kotlin/mcorch/core/Reconciler.kt

WIRING=mcorch.core.DrainWiringTest
RULES=mcorch.core.SaveEvidenceTest
DRAIN=mcorch.core.DrainTest

# The test cases, by name. A mutation names the ones it must redden and no others.
EXIT='nothing leaves advance that has not been through the record-level rule'
CALLER='advanceOnce is private and advance is its only caller'
STEPPED='a pass is stepped with the drain the pass-entry reading voided'
DECIDED='the calls that end a container are decided in one file each'
ADOPTS='a pass entry adopts the confirmation clause of its reading and no more'
RECORDS='a recorded pass cannot carry a confirmed save beside a player count'
RESTARTED='a stop is not re-issued at a container that restarted underneath the drain'

# Single-quoted throughout: these are literals, and one of them contains the
# quoting characters of two languages.

# The record-level rule, in `advance`.
RULE='val recorded = progress.dropSaveContradictedByPlayers()'
# The pass-entry adoption, in `advanceOnce`.
ADOPTION='val observed = drain.adoptSaveClause(reading)'
# The clause the adoption applies, in `adoptSaveClause`.
CLAUSE='is PlayerReading.Occupied -> unconfirmWorldSave()'
# The record-level rule's own predicate.
RECORD_RULE='    if (online == 0 || drain.worldSavedAt == null) return this'
# The re-issue's gate, in `awaitStopped`. Its first line is identical to the one
# in `stop`, so the comment under it is what makes the literal unique.
REISSUE_GATE='            if (!drain.mayStop(contract, observation.startedAt, now, evidenceGap)) {
                // The same rule as in'
UNGATED_REISSUE='            if (!drain.playersEvacuated) {
                // The same rule as in'
NARROWED_REISSUE='            if (!drain.mayStop(contract, observation.startedAt, now, evidenceGap) && !drain.playersEvacuated) {
                // The same rule as in'
# `advance`, with the annotation above it so the literal cannot match `advanceOnce`.
ADVANCE_DECLARATION='    @Suppress("LongParameterList")
    suspend fun advance('
# The tail of Reconciler.kt, for appending to.
RECONCILER_TAIL='        require(drainAttentionAfter.isPositive()) { "drainAttentionAfter must be positive" }
    }
}'
# A second call site, in the shape the class KDoc used to be blind to: a teardown
# helper that reaches the runtime directly. Never called, so nothing but the scan
# can see it — which is the point.
STOP_ELSEWHERE="$RECONCILER_TAIL"'

private suspend fun teardownWithoutDraining(
    node: Node,
    handle: WorkloadHandle,
    grace: kotlin.time.Duration,
) {
    node.stopWorkload(handle, grace)
}'
# The same thing wearing the name of the method it calls, which is what somebody
# writes when they need a stop in two places. Classifying *files* by whether they
# name `fun stopWorkload(` put this one on the "performs it, does not decide it"
# side and passed.
STOP_WRAPPER="$RECONCILER_TAIL"'

private suspend fun stopWorkload(
    node: Node,
    handle: WorkloadHandle,
    grace: kotlin.time.Duration,
) {
    node.stopWorkload(handle, grace)
}'
# The tail of DrainController.kt, for appending to.
CONTROLLER_TAIL='                WorkloadState.RUNNING, WorkloadState.UNKNOWN -> null
            }
        }
    }'
# The path this test's own motivation names — a workload taken away so it can be
# brought up elsewhere — reaching the verb the scan used not to know about.
REMOVE_ELSEWHERE="$CONTROLLER_TAIL"'

private suspend fun makeRoomOnAnotherNode(
    node: Node,
    handle: WorkloadHandle,
) {
    node.removeWorkload(handle)
}'

# name @@ file @@ class @@ testcases that must redden (";"-separated) @@ literal @@ replacement
#
# `@@` because a field holds Kotlin: `|` was the separator until a literal
# containing `||` split into the wrong fields, applied a replacement nobody wrote,
# and reported the resulting compile failure as an UNKNOWN. A delimiter that can
# occur in the payload is a mutation harness lying about its own subject.
MUTATIONS=(
    # An exit above the rule that a prefix match cannot see. The pass that took it
    # records whatever it was handed.
    "D1@@$CONTROLLER@@$WIRING@@$EXIT@@$RULE@@if (progress.occupancy == null) return progress
        $RULE"
    # The same exit spelled as an elvis, which is how it would really be written.
    "D2@@$CONTROLLER@@$WIRING@@$EXIT@@$RULE@@val skip = progress.occupancy ?: return progress
        $RULE"
    # The rule applied to some passes and not others, with the bound name intact.
    "D3@@$CONTROLLER@@$WIRING@@$EXIT@@$RULE@@val recorded = if (progress.drain.playersEvacuated) progress.dropSaveContradictedByPlayers() else progress"
    # The sharpest: a narrowing of the adoption that reads like a careful edit by
    # somebody who has just read the *Declined* paragraph at the call site.
    "D4@@$CONTROLLER@@$WIRING@@$STEPPED@@$ADOPTION@@val observed = if (reading is PlayerReading.Occupied && !drain.playersEvacuated) drain.unconfirmWorldSave() else drain"
    # D4 again, written inside the function the predicate moved into. Same defect,
    # different address, and it is behavioural there rather than structural.
    "D5@@$CONTROLLER@@$RULES@@$ADOPTS@@$CLAUSE@@is PlayerReading.Occupied -> if (playersEvacuated) this else unconfirmWorldSave()"
    "D6@@$RECONCILER@@$WIRING@@$DECIDED@@$RECONCILER_TAIL@@$STOP_ELSEWHERE"
    "D7@@$CONTROLLER@@$WIRING@@every container stop sits behind mayStop, and there are two@@$REISSUE_GATE@@$UNGATED_REISSUE"
    # The other thing the extraction made expressible: the clause applied to the
    # drain from *before* the pass dropped its unusable evidence, which hands the
    # confirmation back.
    "D8@@$CONTROLLER@@$WIRING@@$STEPPED@@$ADOPTION@@val observed = recorded.adoptSaveClause(reading)"
    # The record-level rule excused by a field that is not about this pass. Caught
    # today only because the fixture's drain claims `playersEvacuated`, which is
    # coverage by default until a mutation says otherwise.
    "D9@@$CONTROLLER@@$RULES@@$RECORDS@@$RECORD_RULE@@    if (online == 0 || drain.worldSavedAt == null || drain.playersEvacuated) return this"
    # The gate narrowed instead of deleted. Every structural assertion stays green:
    # the token is there, the count is two, the enclosing set is unchanged. And
    # `playersEvacuated` is true of every drain that reaches `STOPPING`, so this is
    # an unconditional re-issue against a container the drain may never have saved.
    "D10@@$CONTROLLER@@$DRAIN@@$RESTARTED@@$REISSUE_GATE@@$NARROWED_REISSUE"
    # The single exit's precondition, both halves. Widening the visibility opens the
    # set of possible callers to the module.
    "D11@@$CONTROLLER@@$WIRING@@$CALLER@@    private suspend fun advanceOnce(@@    internal suspend fun advanceOnce("
    # And a second call: \"one more pass\" for a drain that did nothing, which
    # discards the first pass's record and keeps its side effects.
    "D12@@$CONTROLLER@@$WIRING@@$CALLER;$EXIT@@$RULE@@val again =
            if (progress.workDone) {
                progress
            } else {
                advanceOnce(
                    subject = subject,
                    node = node,
                    observation = observation,
                    current = current,
                    cause = cause,
                    lastProbedAt = lastProbedAt,
                    hadContainer = hadContainer,
                )
            }
        val recorded = again.dropSaveContradictedByPlayers()"
    "D13@@$RECONCILER@@$WIRING@@$DECIDED@@$RECONCILER_TAIL@@$STOP_WRAPPER"
    "D14@@$CONTROLLER@@$WIRING@@$DECIDED@@$CONTROLLER_TAIL@@$REMOVE_ELSEWHERE"
    "C1@@$CONTROLLER@@$WIRING@@$EXIT@@$RULE@@val recorded = progress"
    "C2@@$CONTROLLER@@$WIRING@@$STEPPED@@$ADOPTION@@val observed = drain"
    "C3@@$CONTROLLER@@$RULES@@$ADOPTS@@$CLAUSE@@is PlayerReading.Occupied -> this"
)

restore() {
    for backup in "$BACKUP_DIR"/*.kt; do
        [[ -e "$backup" ]] || continue
        case "$(basename -- "$backup")" in
            DrainController.kt) cp -- "$backup" "$REPO_ROOT/$CONTROLLER" ;;
            Reconciler.kt) cp -- "$backup" "$REPO_ROOT/$RECONCILER" ;;
        esac
    done
}

cleanup() {
    restore
    rm -rf -- "$BACKUP_DIR"
}
trap cleanup EXIT INT TERM

cp -- "$REPO_ROOT/$CONTROLLER" "$BACKUP_DIR/DrainController.kt"
cp -- "$REPO_ROOT/$RECONCILER" "$BACKUP_DIR/Reconciler.kt"

apply() {
    LITERAL="$2" REPLACEMENT="$3" python3 - "$1" <<'PY'
import os, sys
path = sys.argv[1]
literal, replacement = os.environ["LITERAL"], os.environ["REPLACEMENT"]
with open(path, encoding="utf-8") as handle:
    text = handle.read()
found = text.count(literal)
if found != 1:
    sys.exit(
        f"  the source contains {found} occurrences of:\n    {literal}\n"
        "  re-derive this mutation against the current source before trusting a green run"
    )
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text.replace(literal, replacement))
PY
}

# The test cases that went red in a report, one per line, without the `()` the
# runner appends. Scanned rather than parsed: the file is this build's own output,
# and a dev script should need nothing but a shell.
reddened() {
    awk 'match($0, /<testcase name="[^"]*"/) {
             name = substr($0, RSTART + 16, RLENGTH - 17)
         }
         (/<failure/ || /<error/) && name != "" {
             sub(/\(\)$/, "", name)
             print name
             name = ""
         }' "$1" | sort -u
}

# 0 if the named class reddened exactly the test cases the mutation claims.
judge() {
    local name="$1" class="$2" expected="$3"
    local report="$RESULTS/TEST-$class.xml"

    if [[ ! -f "$report" ]]; then
        echo "  UNKNOWN — no report for $class; the mutation probably did not compile" >&2
        return 1
    fi

    local red want
    red="$(reddened "$report")"
    want="$(printf '%s' "$expected" | tr ';' '\n' | sort -u)"

    if [[ -z "$red" ]]; then
        echo "  GREEN — $class did not catch $name" >&2
        return 1
    fi
    if [[ "$red" != "$want" ]]; then
        # The failure this exists for: a red set that is not the claimed one says
        # nothing about the assertion under test, however red it is.
        echo "  MISCAUGHT — $name claims:" >&2
        echo "$want" | sed 's/^/    /' >&2
        echo "  and reddened:" >&2
        echo "$red" | sed 's/^/    /' >&2
        return 1
    fi
    echo "$red" | sed 's/^/  red: /'
    return 0
}

# Runs the already-applied mutation's test class, leaving the report behind.
execute() {
    local class="$1"

    rm -f -- "$RESULTS/TEST-$class.xml"
    # --rerun because the only input that changed is a source file the test reads
    # at runtime, which Gradle has no way to know about.
    (cd "$REPO_ROOT" && ./gradlew --quiet --console=plain :core:test --tests "$class" --rerun) \
        >/dev/null 2>&1 || true
}

# The verdict's own red-proof.
#
# Breaks the whole test class the way renaming `advance` does — two declarations of
# it, so `rangeOf`'s exactly-one check throws before any assertion runs — *and*
# applies C1 on top, so the run is a real mutation scored under a class that is
# failing for an unrelated reason. Reading the verdict per class would call it
# caught. Reading it per test case must refuse it, because the assertion C1 is
# about never ran.
self_test() {
    echo "== S1 (the verdict, proved able to fail)"
    restore
    apply "$REPO_ROOT/$CONTROLLER" "$ADVANCE_DECLARATION" '    @Suppress("unused")
    private fun advance(neverCalled: Int): Int = neverCalled

'"$ADVANCE_DECLARATION" || return 1
    apply "$REPO_ROOT/$CONTROLLER" "$RULE" 'val recorded = progress' || return 1

    execute "$WIRING"

    # The instrument has to be shown to move before its refusal means anything: a
    # self-test whose own mutation did not compile refuses for the wrong reason and
    # reads exactly like a working one.
    local report="$RESULTS/TEST-$WIRING.xml" red
    if [[ ! -f "$report" ]]; then
        echo "  UNKNOWN — the self-test's own mutation left no report; it probably did not compile" >&2
        return 1
    fi
    red="$(reddened "$report")"
    if [[ -z "$red" ]]; then
        echo "  the class did not go red at all, so this proves nothing about the verdict" >&2
        return 1
    fi
    if judge "S1" "$WIRING" "$EXIT" >/dev/null 2>&1; then
        echo "  the verdict scored a class-wide failure as 'caught' — it is per class again, and" >&2
        echo "  every result this script has ever printed is consistent with total vacuity" >&2
        return 1
    fi
    echo "  ${red//$'\n'/, } went red; C1's claim was refused rather than counted"
    return 0
}

failures=0
ran=0
selected=("$@")

wanted() {
    [[ ${#selected[@]} -eq 0 ]] || [[ " ${selected[*]} " == *" $1 "* ]]
}

for mutation in "${MUTATIONS[@]}"; do
    name=${mutation%%@@*}
    rest=${mutation#*@@}
    file=${rest%%@@*}
    rest=${rest#*@@}
    class=${rest%%@@*}
    rest=${rest#*@@}
    expected=${rest%%@@*}
    rest=${rest#*@@}
    literal=${rest%%@@*}
    replacement=${rest#*@@}

    wanted "$name" || continue

    # Six fields, or the entry has been mis-split and every field after the break
    # is something nobody wrote. That is how the `||` collision reached a Gradle
    # run at all.
    if [[ $(grep -c '@@' <<<"${mutation//@@/$'\n@@\n'}") -ne 5 ]]; then
        echo "== $name" >&2
        echo "  malformed entry: expected 6 fields separated by @@" >&2
        failures=$((failures + 1))
        continue
    fi

    echo "== $name"
    restore
    if ! apply "$REPO_ROOT/$file" "$literal" "$replacement"; then
        failures=$((failures + 1))
        continue
    fi
    ran=$((ran + 1))
    execute "$class"
    judge "$name" "$class" "$expected" || failures=$((failures + 1))
done

if wanted S1; then
    ran=$((ran + 1))
    self_test || failures=$((failures + 1))
fi

restore

if [[ $ran -eq 0 && $failures -eq 0 ]]; then
    echo "no mutation ran" >&2
    exit 1
fi
if [[ $failures -ne 0 ]]; then
    echo "$failures mutations were not caught as claimed" >&2
    exit 1
fi
echo "all $ran mutations caught, each by the test case it names"
