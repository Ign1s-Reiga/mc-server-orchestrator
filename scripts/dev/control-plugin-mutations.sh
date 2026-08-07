#!/usr/bin/env bash
# Red-proof for the proxy control channel's delivery assertions.
#
# Two defects reached this repo that every unit test in it agreed with, and both
# were about something *absent* rather than about logic:
#
#   1. the planner asked for the Velocity control plugin and `LocalNode` dropped
#      the request one layer down, so the JAR never reached a container;
#   2. `MCORCH_CONTROL_TOKEN` was never set on the container, so the plugin ran
#      with authentication off while `:core` politely sent a bearer token it
#      ignored.
#
# Both produce a proxy that comes up perfectly well, serves players, and has no
# working control channel — and drain steps 2, 4 and 6 run through that channel,
# so every backend behind such a proxy is undrainable. Twenty-three static audit
# rounds passed over them.
#
# The tests written for them are cheap to satisfy by accident, which is the whole
# reason this script exists: "the planner emits a mount" passed *throughout the
# defect*. So each mutation below is an edit a careful person might really make,
# each names the test cases it must redden, and the red set has to match exactly.
#
#   P1  the original defect in its new home: the asset loop that produces no mount.
#   P2  the mount produced writable. The artefact a container could rewrite is one
#       that decides what the next container loads.
#   P3  the refusal downgraded to a warning — the plausible "don't fail the create,
#       the file might turn up" edit. A mount of a hole is what the runtime makes
#       of it, and the proxy starts.
#   P4  the planner stops asking for the asset at all, judged by the planner's own
#       assertion...
#   P5  ...and the same edit judged by the reconcile-level one, because a planner
#       nobody wired is the other half of defect 1 and a unit test of the planner
#       cannot see it.
#   P6  the token dropped, judged at the planner...
#   P7  ...and at the loop.
#   P8  `TYPE` dropped. Without it the image runs BungeeCord: a proxy that starts,
#       ignores modern forwarding, and cannot load a Velocity plugin — so P1 and P6
#       could both be fixed and the control endpoint would still not exist.
#   P9  a fictional environment variable re-added. `VELOCITY_PORT` configured
#       nothing and read as configuration, which is how a wrong player port
#       survived: it looked applied.
#   P10 **the defect in its original location** — the mount derivation rebuilt
#       inside `LocalNode`, where no `:core` test may follow it (`mcorch.cri` types
#       are that file's alone). This is the one no behavioural test in the module
#       can catch, and the reason a structural assertion is there at all.
#   C1  control: the asset mount deleted outright, so a run that reddens nothing
#       here is not reaching the assertions.
#   S1  the self-test. A real mutation, judged against a claim it does not satisfy;
#       the verdict has to refuse the result rather than score it caught.
#
# There is deliberately **no mutation for the token travelling in `env`** — the
# "helpful" edit that puts the coordinate in plain environment. `WorkloadSpec`'s
# constructor refuses a name declared both plainly and as a secret, so the
# mutation throws at construction and reddens a different set than it claims. That
# is a check moved into the type, which is where this project prefers it; the
# reason is written here so the gap reads as a ruling rather than an oversight.
#
# The verdict is read from the JUnit XML per *test case*, never from Gradle's exit
# status and never per class: a mutation that fails to compile also exits
# non-zero, and one broken shared helper makes every entry "caught" at once.
#
#   ./scripts/dev/control-plugin-mutations.sh          run all of them, and S1
#   ./scripts/dev/control-plugin-mutations.sh P1 C1    run some

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
RESULTS="$REPO_ROOT/core/build/test-results/test"
BACKUP_DIR="$(mktemp -d -t control-plugin-XXXXXX)"

PATHS=core/src/main/kotlin/mcorch/core/node/HostPaths.kt
NODE=core/src/main/kotlin/mcorch/core/node/LocalNode.kt
PLANNER=core/src/main/kotlin/mcorch/core/proxy/VelocityWorkloadPlanner.kt

MOUNTS=mcorch.core.node.WorkloadMountsTest
PLAN=mcorch.core.proxy.VelocityWorkloadPlannerTest
LOOP=mcorch.core.ProxyReconcileTest

# The test cases, by name. A mutation names the ones it must redden and no others.
MOUNTED="the proxy's control plugin is mounted read-only from the node's asset root"
MISSING='a workload whose asset the node does not have is refused permanently'
DIRECTORY='a directory where the artefact should be is refused too, rather than mounted'
ONE_DERIVATION='every mount the local node hands the runtime comes from the one derivation'
REQUESTED='the control plugin is requested as an asset, and the proxy holds no storage'
COORDINATE='the control token travels as a coordinate in secretEnv, never in the environment'
WHICH_PROXY='the image is told which proxy to run'
NOTHING_ELSE='the environment carries nothing the image does not read'
CREATED='the proxy container the loop creates carries the plugin and the token, once'
TRANSIENT='a proxy create that fails transiently requeues and gets through on the next pass'

# Single-quoted throughout: these are literals, and several contain Kotlin.

# The asset half of the mount derivation.
ASSET_LOOP='            for (mount in spec.assets) {
                add(
                    HostMount(
                        containerPath = mount.destination,
                        hostPath = assetFile(node, assetRoot, spec, mount).toString(),
                        // Never writable. See `AssetMount`.
                        readOnly = true,
                    ),
                )
            }'
# The same loop, filtered down to nothing by a predicate that reads like caution:
# "only mount an asset the workload actually declared a directory for". Every
# request does, so this drops all of them — and it keeps the loop, the `add`, and
# the field copy, which is what a structural reading would look for.
ASSET_LOOP_NARROWED='            for (mount in spec.assets.filter { it.directory.startsWith(assetRoot.toString()) }) {
                add(
                    HostMount(
                        containerPath = mount.destination,
                        hostPath = assetFile(node, assetRoot, spec, mount).toString(),
                        readOnly = true,
                    ),
                )
            }'
# The artefact mounted writable.
READ_ONLY='                        readOnly = true,'
WRITABLE='                        readOnly = false,'
# The refusal, and the "let the create through, the file may turn up" edit.
REFUSAL='        if (!usable) {
            throw missingAsset(node, spec, mount, path, "there is no readable file there")
        }'
WARNING='        if (!usable) {
            LOG.warn("the {} artefact is not on this node yet", mount.asset)
        }'
# What the planner asks for, and the shape of it going quiet.
ASSET_REQUEST='            assets = listOf(AssetMount(WorkloadAsset.VELOCITY_CONTROL_PLUGIN, PLUGIN_DIRECTORY)),'
NO_ASSET='            assets = emptyList(),'
# The token, as coordinates.
TOKEN_REQUEST='                    spec.control.tokenSecret?.let { put(CONTROL_TOKEN, it) }'
NO_TOKEN='                    Unit'
# The variable without which the image runs a different proxy.
TYPE_REQUEST='                    put(TYPE, TYPE_VELOCITY)'
NO_TYPE='                    Unit'
# A variable that configures nothing, back where it used to read as configuration.
FICTION='                    put(TYPE, TYPE_VELOCITY)
                    put("VELOCITY_PORT", spec.network.port.toString())'
# The node'"'"'s field copy, and the derivation rebuilt inside it — the defect
# exactly as it was, in the file `:core` tests cannot follow it into.
FIELD_COPY='        HostPaths.mounts(name, volumeRoot, assetRoot, spec).map { mount ->
            VolumeMount(
                containerPath = mount.containerPath,
                hostPath = mount.hostPath,
                readOnly = mount.readOnly,
            )
        }'
REBUILT_DERIVATION='        when (val storage = spec.storage) {
            is StorageRequest.Persistent ->
                listOf(
                    VolumeMount(
                        containerPath = storage.mountPath,
                        hostPath = HostPaths.volumePath(volumeRoot, storage.volume).toString(),
                    ),
                )

            StorageRequest.Ephemeral -> emptyList()
        }'

# name @@ file @@ class @@ testcases that must redden (";"-separated) @@ literal @@ replacement
MUTATIONS=(
    "P1@@$PATHS@@$MOUNTS@@$MOUNTED;$MISSING;$DIRECTORY@@$ASSET_LOOP@@$ASSET_LOOP_NARROWED"
    "P2@@$PATHS@@$MOUNTS@@$MOUNTED@@$READ_ONLY@@$WRITABLE"
    "P3@@$PATHS@@$MOUNTS@@$MISSING;$DIRECTORY@@$REFUSAL@@$WARNING"
    "P4@@$PLANNER@@$PLAN@@$REQUESTED@@$ASSET_REQUEST@@$NO_ASSET"
    "P5@@$PLANNER@@$LOOP@@$CREATED;$TRANSIENT@@$ASSET_REQUEST@@$NO_ASSET"
    "P6@@$PLANNER@@$PLAN@@$COORDINATE@@$TOKEN_REQUEST@@$NO_TOKEN"
    "P7@@$PLANNER@@$LOOP@@$CREATED@@$TOKEN_REQUEST@@$NO_TOKEN"
    "P8@@$PLANNER@@$PLAN@@$WHICH_PROXY;$NOTHING_ELSE@@$TYPE_REQUEST@@$NO_TYPE"
    "P9@@$PLANNER@@$PLAN@@$NOTHING_ELSE@@$TYPE_REQUEST@@$FICTION"
    "P10@@$NODE@@$MOUNTS@@$ONE_DERIVATION@@$FIELD_COPY@@$REBUILT_DERIVATION"
    "C1@@$PATHS@@$MOUNTS@@$MOUNTED;$MISSING;$DIRECTORY@@$ASSET_LOOP@@            // the assets, dropped"
)

restore() {
    for backup in "$BACKUP_DIR"/*.kt; do
        [[ -e "$backup" ]] || continue
        case "$(basename -- "$backup")" in
            HostPaths.kt) cp -- "$backup" "$REPO_ROOT/$PATHS" ;;
            LocalNode.kt) cp -- "$backup" "$REPO_ROOT/$NODE" ;;
            VelocityWorkloadPlanner.kt) cp -- "$backup" "$REPO_ROOT/$PLANNER" ;;
        esac
    done
}

# The reports left behind describe *mutated* source and carry nothing that says
# so. One from the sibling script was read as a regression on clean source before
# a re-run showed otherwise. They go on the way out, with the source.
discard_reports() {
    for class in "$MOUNTS" "$PLAN" "$LOOP"; do
        rm -f -- "$RESULTS/TEST-$class.xml"
    done
}

cleanup() {
    restore
    discard_reports
    rm -rf -- "$BACKUP_DIR"
}
# The same correction as the sibling script's, applied here because a harness bug is
# fixed in every harness: `trap cleanup INT TERM` cleans up and **returns**, so the run
# carries on past the kill with its backups deleted and leaves mutations behind. These
# stop it. `cleanup` is idempotent, so the EXIT trap firing after them costs nothing.
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

cp -- "$REPO_ROOT/$PATHS" "$BACKUP_DIR/HostPaths.kt"
cp -- "$REPO_ROOT/$NODE" "$BACKUP_DIR/LocalNode.kt"
cp -- "$REPO_ROOT/$PLANNER" "$BACKUP_DIR/VelocityWorkloadPlanner.kt"

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

# The test cases that went red in a report, one per line, without the parameter
# list the runner appends.
#
# **Not just `()`.** A test that takes an injected parameter is reported as
# `the plugin is mounted …(Path)` — every `@TempDir` test here does — and a
# stripper that only knows the empty pair leaves a name no claim can ever match.
# Every entry then reads MISCAUGHT, which is indistinguishable from a real
# finding until somebody reads the two lists side by side.
reddened() {
    awk 'match($0, /<testcase name="[^"]*"/) {
             name = substr($0, RSTART + 16, RLENGTH - 17)
         }
         (/<failure/ || /<error/) && name != "" {
             sub(/\([^()]*\)$/, "", name)
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
        echo "  MISCAUGHT — $name claims:" >&2
        echo "$want" | sed 's/^/    /' >&2
        echo "  and reddened:" >&2
        echo "$red" | sed 's/^/    /' >&2
        return 1
    fi
    echo "$red" | sed 's/^/  red: /'
    return 0
}

execute() {
    local class="$1"

    rm -f -- "$RESULTS/TEST-$class.xml"
    # --rerun because a source file is the only input that changed, and one of
    # these tests reads a source file at runtime.
    (cd "$REPO_ROOT" && ./gradlew --quiet --console=plain :core:test --tests "$class" --rerun) \
        >/dev/null 2>&1 || true
}

failures=0
ran=0
selected=("$@")

wanted() {
    [[ ${#selected[@]} -eq 0 ]] || [[ " ${selected[*]} " == *" $1 "* ]]
}

# The verdict's own red-proof: a real mutation judged against a claim it does not
# satisfy. P2 reddens exactly one case; claiming two means the run says nothing
# about either, and the verdict has to refuse it rather than count it caught.
#
# It also has to check that the class went red at all. A self-test whose own
# mutation failed to compile refuses for the wrong reason and reads exactly like a
# working one.
self_test() {
    echo "== S1 (the verdict, proved able to fail)"
    restore
    apply "$REPO_ROOT/$PATHS" "$READ_ONLY" "$WRITABLE" || return 1
    execute "$MOUNTS"

    local red
    red="$(reddened "$RESULTS/TEST-$MOUNTS.xml" 2>/dev/null || true)"
    if [[ -z "$red" ]]; then
        echo "  the class did not go red at all, so this proves nothing about the verdict" >&2
        return 1
    fi
    if judge "S1" "$MOUNTS" "$MOUNTED;$MISSING" >/dev/null 2>&1; then
        echo "  the verdict accepted a red set that is not the claimed one; it is not comparing" >&2
        return 1
    fi
    echo "  ${red//$'\n'/, } went red; a claim of two cases was refused rather than counted"
    return 0
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
    # is something nobody wrote.
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
