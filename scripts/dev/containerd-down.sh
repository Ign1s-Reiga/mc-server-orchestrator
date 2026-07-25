#!/usr/bin/env bash
# Stop this project's dedicated containerd.
#
# Only ever touches the instance containerd-up.sh created — it stops one
# specific process, verified by PID and config path. It never prunes, never
# removes containers in bulk, and never touches a system containerd or Docker.
#
#   ./scripts/dev/containerd-down.sh            stop the daemon
#   ./scripts/dev/containerd-down.sh --purge    stop, then delete root + state
#   ./scripts/dev/containerd-down.sh --purge --force   purge even with live containers
#   ./scripts/dev/containerd-down.sh --remove-unit     also remove the systemd unit
#
# Stopping containerd does NOT kill running containers — their shims keep them
# alive so the daemon can be restarted under them. Use --purge to actually
# discard this instance's state.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/dev/containerd-env.sh
. "${SCRIPT_DIR}/containerd-env.sh"

PURGE=0
FORCE=0
REMOVE_UNIT=0

while [ $# -gt 0 ]; do
    case "$1" in
        --purge)       PURGE=1 ;;
        --force)       FORCE=1 ;;
        --remove-unit) REMOVE_UNIT=1 ;;
        -h|--help)     sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)             die "unknown argument: $1 (try --help)" ;;
    esac
    shift
done

resolve_sudo
ensure_sudo_auth

# ─── what is still running on it ─────────────────────────────────────────────

live_containers() {
    command -v crictl >/dev/null 2>&1 || { echo 0; return; }
    [ -S "${SOCKET}" ] || { echo 0; return; }
    crictl --runtime-endpoint "${CRI_ENDPOINT}" ps -q 2>/dev/null | grep -c . || echo 0
}

if containerd_running; then
    n="$(live_containers)"
    if [ "${n}" -gt 0 ]; then
        warn "${n} container(s) are still running on this instance."
        warn "Stopping containerd leaves them alive under their shims; --purge would not."
        if [ "${PURGE}" -eq 1 ] && [ "${FORCE}" -eq 0 ]; then
            die "refusing to --purge while containers are running. Stop them first, or pass --force if you are certain this is throwaway dev state."
        fi
    fi
else
    log "not running"
fi

# ─── stop ────────────────────────────────────────────────────────────────────

stopped_via_systemd=0
if have_systemd && [ -f "${SYSTEMD_UNIT_FILE}" ]; then
    if systemctl is-active --quiet "${SYSTEMD_UNIT}" 2>/dev/null; then
        log "stopping systemd unit ${SYSTEMD_UNIT}"
        ${SUDO} systemctl stop "${SYSTEMD_UNIT}"
        stopped_via_systemd=1
    fi
fi

if [ "${stopped_via_systemd}" -eq 0 ]; then
    # read_pid only returns a PID whose /proc/PID/cmdline references OUR config
    # file, so a stale pidfile after PID reuse cannot make us signal something
    # unrelated.
    if pid="$(read_pid)"; then
        log "stopping containerd (pid ${pid})"
        ${SUDO} kill -TERM "${pid}" 2>/dev/null || true
        for _ in $(seq 1 100); do
            kill -0 "${pid}" 2>/dev/null || break
            sleep 0.1
        done
        if kill -0 "${pid}" 2>/dev/null; then
            warn "did not exit on SIGTERM after 10s — sending SIGKILL"
            ${SUDO} kill -KILL "${pid}" 2>/dev/null || true
            sleep 0.5
        fi
    elif [ -f "${PID_FILE}" ]; then
        warn "pidfile ${PID_FILE} does not point at our containerd — leaving that process alone"
    fi
fi

# ─── verify and tidy ─────────────────────────────────────────────────────────

if containerd_running; then
    die "containerd is still running — refusing to report success"
fi

${SUDO} rm -f "${SOCKET}" "${TTRPC_SOCKET}" "${PID_FILE}"
log "stopped"

if [ "${REMOVE_UNIT}" -eq 1 ] && [ -f "${SYSTEMD_UNIT_FILE}" ]; then
    log "removing ${SYSTEMD_UNIT_FILE}"
    ${SUDO} systemctl disable "${SYSTEMD_UNIT}" >/dev/null 2>&1 || true
    ${SUDO} rm -f "${SYSTEMD_UNIT_FILE}"
    ${SUDO} systemctl daemon-reload
fi

if [ "${PURGE}" -eq 1 ]; then
    # Scoped to this instance's own directories. These are the paths from
    # containerd-env.sh, which is also what created them.
    log "purging state for ${INSTANCE}"
    for d in "${ROOT_DIR}" "${STATE_DIR}" "${RUN_DIR}"; do
        case "${d}" in
            /|/usr|/etc|/var|/run|/var/lib|"")
                die "refusing to remove suspicious path: '${d}' — check containerd-env.sh" ;;
        esac
        [ -e "${d}" ] || continue
        log "  rm -rf ${d}"
        ${SUDO} rm -rf "${d}"
    done
    log "purged. Config at ${CONF_DIR} was left in place; remove it by hand if you want a fully clean slate."
fi
