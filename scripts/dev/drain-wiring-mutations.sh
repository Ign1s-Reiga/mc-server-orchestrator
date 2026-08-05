#!/usr/bin/env bash
# Red-proof for core/src/test/kotlin/mcorch/core/DrainWiringTest.kt.
#
# That test asserts the *shape* of DrainController's safety claims — that each
# rule is applied unconditionally, that the value it produces is the value that
# leaves, and that every container stop sits behind `mayStop` and nowhere else in
# the module. A structural test cannot be sabotaged behaviourally, so its
# red-proof has to sabotage the wiring, and one sabotage is not enough: these
# assertions fail independently of each other, and four of the mutations below
# restored round 18's critical while the whole suite, DrainWiringTest included,
# stayed green when the twentieth audit found them.
#
# Each mutation is applied to a working copy of one source file, the test class
# that must catch it is run, and the file is restored — mutated source is never
# committed and never survives this script, including on failure or interrupt.
#
#   D1..D4  the wiring defects the twentieth audit demonstrated. DrainWiringTest.
#   D5      the same narrowing written where the fix moved the predicate to. It is
#           behaviour now, so SaveEvidenceTest is what has to catch it — a remedy
#           that only relocates a defect has to be traced to its new home.
#   D6      a stop added outside DrainController, which is where a drain audit
#           looks and where a file-scoped scan cannot see.
#   D7      a stop that keeps its place and loses its gate.
#   C1..C3  controls: the rule deleted outright, once per assertion arm. If these
#           do not redden, the harness is not reaching the assertions at all.
#
# The verdict is read from the JUnit XML, never from Gradle's exit status: a
# mutation that fails to compile also exits non-zero, and calling that "caught"
# would be the same false green in a different costume. No XML is an unknown.
#
# A mutation that no longer applies is a failure too, and a deliberate one: it
# means the source it was written against has moved, and whether the defect it
# describes is still expressible has to be decided by a human rather than assumed.
#
#   ./scripts/dev/drain-wiring-mutations.sh          run all of them
#   ./scripts/dev/drain-wiring-mutations.sh D3 C1    run some

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
RESULTS="$REPO_ROOT/core/build/test-results/test"
BACKUP_DIR="$(mktemp -d -t drain-wiring-XXXXXX)"

CONTROLLER=core/src/main/kotlin/mcorch/core/DrainController.kt
RECONCILER=core/src/main/kotlin/mcorch/core/Reconciler.kt

WIRING=mcorch.core.DrainWiringTest
RULES=mcorch.core.SaveEvidenceTest

# Single-quoted throughout: these are literals, and one of them contains the
# quoting characters of two languages.

# The record-level rule, in `advance`.
RULE='val recorded = progress.dropSaveContradictedByPlayers()'
# The pass-entry adoption, in `advanceOnce`.
ADOPTION='val observed = drain.adoptSaveClause(reading)'
# The clause the adoption applies, in `adoptSaveClause`.
CLAUSE='is PlayerReading.Occupied -> unconfirmWorldSave()'
# The re-issue's gate, in `awaitStopped`. Its first line is identical to the one
# in `stop`, so the comment under it is what makes the literal unique.
REISSUE_GATE='            if (!drain.mayStop(contract, observation.startedAt, now, evidenceGap)) {
                // The same rule as in'
UNGATED_REISSUE='            if (!drain.playersEvacuated) {
                // The same rule as in'
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

# name | file | the class that must redden | the line to find | what to put in its place
MUTATIONS=(
    # An exit above the rule that a prefix match cannot see. The pass that took it
    # records whatever it was handed.
    "D1|$CONTROLLER|$WIRING|$RULE|if (progress.occupancy == null) return progress
        $RULE"
    # The same exit spelled as an elvis, which is how it would really be written.
    "D2|$CONTROLLER|$WIRING|$RULE|val skip = progress.occupancy ?: return progress
        $RULE"
    # The rule applied to some passes and not others, with the bound name intact.
    "D3|$CONTROLLER|$WIRING|$RULE|val recorded = if (progress.drain.playersEvacuated) progress.dropSaveContradictedByPlayers() else progress"
    # The sharpest: a narrowing of the adoption that reads like a careful edit by
    # somebody who has just read the *Declined* paragraph at the call site.
    "D4|$CONTROLLER|$WIRING|$ADOPTION|val observed = if (reading is PlayerReading.Occupied && !drain.playersEvacuated) drain.unconfirmWorldSave() else drain"
    # D4 again, written inside the function the predicate moved into. Same defect,
    # different address, and it is behavioural there rather than structural.
    "D5|$CONTROLLER|$RULES|$CLAUSE|is PlayerReading.Occupied -> if (playersEvacuated) this else unconfirmWorldSave()"
    "D6|$RECONCILER|$WIRING|$RECONCILER_TAIL|$STOP_ELSEWHERE"
    "D7|$CONTROLLER|$WIRING|$REISSUE_GATE|$UNGATED_REISSUE"
    "C1|$CONTROLLER|$WIRING|$RULE|val recorded = progress"
    "C2|$CONTROLLER|$WIRING|$ADOPTION|val observed = drain"
    "C3|$CONTROLLER|$RULES|$CLAUSE|is PlayerReading.Occupied -> this"
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

failures=0
ran=0

for mutation in "${MUTATIONS[@]}"; do
    name=${mutation%%|*}
    rest=${mutation#*|}
    file=${rest%%|*}
    rest=${rest#*|}
    class=${rest%%|*}
    rest=${rest#*|}
    literal=${rest%%|*}
    replacement=${rest#*|}
    report="$RESULTS/TEST-$class.xml"

    if [[ $# -gt 0 ]] && [[ " $* " != *" $name "* ]]; then
        continue
    fi

    echo "== $name"
    restore
    if ! apply "$REPO_ROOT/$file" "$literal" "$replacement"; then
        failures=$((failures + 1))
        continue
    fi
    ran=$((ran + 1))

    rm -f -- "$report"
    # --rerun because the only input that changed is a source file the test reads
    # at runtime, which Gradle has no way to know about.
    (cd "$REPO_ROOT" && ./gradlew --quiet --console=plain :core:test --tests "$class" --rerun) \
        >/dev/null 2>&1 || true

    if [[ ! -f "$report" ]]; then
        echo "  UNKNOWN — no report for $class; the mutation probably did not compile" >&2
        failures=$((failures + 1))
    elif grep -q "<failure" "$report"; then
        # Which assertion caught it, because "something went red" is not the claim.
        # Scanned rather than parsed: the file is this build's own output, and a
        # dev script should need nothing but a shell.
        awk 'match($0, /<testcase name="[^"]*"/) {
                 name = substr($0, RSTART + 16, RLENGTH - 17)
             }
             /<failure/ && name != "" { print "  red: " name; name = "" }' "$report"
    else
        echo "  GREEN — $class did not catch $name" >&2
        failures=$((failures + 1))
    fi
done

restore

if [[ $ran -eq 0 && $failures -eq 0 ]]; then
    echo "no mutation ran" >&2
    exit 1
fi
if [[ $failures -ne 0 ]]; then
    echo "$failures mutations were not caught" >&2
    exit 1
fi
echo "all $ran mutations caught"
