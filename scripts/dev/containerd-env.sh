#!/usr/bin/env bash
# Shared configuration for this project's dedicated containerd instance.
#
# Sourced by containerd-up.sh and containerd-down.sh. The paths live in exactly
# one place on purpose: if `up` and `down` ever disagreed about where the socket
# or state lives, `down` would silently fail to stop what `up` started, or worse,
# delete something it did not create.
#
# This instance is deliberately separate from any system containerd and from
# Docker Desktop's. Nothing here touches /run/containerd or /var/lib/containerd.

set -euo pipefail

# ─── identity ────────────────────────────────────────────────────────────────
INSTANCE="mcorch-dev"

# ─── pinned versions ─────────────────────────────────────────────────────────
# containerd is pinned to the release whose CRI API matches the proto vendored
# in :cri (see cri/PROTO_SOURCE.md — containerd 2.3.3 -> k8s.io/cri-api v0.36.0).
# crictl tracks Kubernetes minor versions, so 1.36.x is the line that matches
# cri-api v0.36. Do not bump containerd here without re-vendoring the proto.
CONTAINERD_VERSION="2.3.3"
RUNC_VERSION="1.5.1"
CNI_PLUGINS_VERSION="1.9.1"
CRICTL_VERSION="1.36.0"

# sha256 of each release asset, pinned. Verified against upstream on 2026-07-26.
# These are checked before anything is installed as root — a download that does
# not match is a hard failure, never a warning.
CONTAINERD_SHA256="ebf6e710056312628eaf6fb4a1c32f0a4ae5f812568321be4029389d66fc7c7c"
RUNC_SHA256="177df879d50c913eb205e898d5c1c05a18f574053c0ce5524c471208eaf06f6f"
CNI_PLUGINS_SHA256="b98f74a0f8522f0a83867178729c1aa70f2158f90c45a2ca8fa791db1c76b303"
CRICTL_SHA256="83855e114566a8a8c44c548d515670f51de3a5e1da8b2effb59870e2f10c25a3"

# ─── paths (all namespaced to this instance) ─────────────────────────────────
RUN_DIR="/run/${INSTANCE}"
STATE_DIR="${RUN_DIR}/state"
ROOT_DIR="/var/lib/${INSTANCE}/containerd"
CONF_DIR="/etc/${INSTANCE}"
CONFIG_FILE="${CONF_DIR}/containerd.toml"
CNI_CONF_DIR="${CONF_DIR}/cni/net.d"
CNI_BIN_DIR="/opt/cni/bin"
CNI_STAMP="${CNI_BIN_DIR}/.${INSTANCE}-version"

SOCKET="${RUN_DIR}/containerd.sock"
TTRPC_SOCKET="${SOCKET}.ttrpc"
PID_FILE="${RUN_DIR}/containerd.pid"
LOG_FILE="${RUN_DIR}/containerd.log"

# What :cri connects to, and what crictl needs.
CRI_ENDPOINT="unix://${SOCKET}"

SYSTEMD_UNIT="${INSTANCE}-containerd"
SYSTEMD_UNIT_FILE="/etc/systemd/system/${SYSTEMD_UNIT}.service"

INSTALL_BIN_DIR="/usr/local/bin"
INSTALL_SBIN_DIR="/usr/local/sbin"

# ─── output helpers ──────────────────────────────────────────────────────────
if [ -t 2 ]; then
    _c_reset=$'\033[0m'; _c_red=$'\033[31m'; _c_yellow=$'\033[33m'; _c_dim=$'\033[2m'
else
    _c_reset=""; _c_red=""; _c_yellow=""; _c_dim=""
fi

log()  { printf '%s==>%s %s\n' "${_c_dim}" "${_c_reset}" "$*" >&2; }
warn() { printf '%swarn:%s %s\n' "${_c_yellow}" "${_c_reset}" "$*" >&2; }
die()  { printf '%serror:%s %s\n' "${_c_red}" "${_c_reset}" "$*" >&2; exit 1; }

# ─── environment probes ──────────────────────────────────────────────────────

# The canonical "was this system booted with systemd" check — this is what
# sd_booted(3) itself does. WSL2 only has systemd when /etc/wsl.conf sets
# [boot] systemd=true, and that requires WSL 0.67.6 or newer.
have_systemd() {
    [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1
}

cgroup_v2() {
    [ "$(stat -fc %T /sys/fs/cgroup 2>/dev/null)" = "cgroup2fs" ]
}

is_wsl() {
    [ -n "${WSL_DISTRO_NAME:-}" ] || grep -qi microsoft /proc/version 2>/dev/null
}

# Resolve how to obtain root. Note that on a stock WSL2 Ubuntu this will PROMPT
# for a password — the scripts check this up front rather than discovering it
# halfway through an install.
SUDO=""
resolve_sudo() {
    if [ "$(id -u)" -eq 0 ]; then
        SUDO=""
        return 0
    fi
    command -v sudo >/dev/null 2>&1 || die "not root and sudo is not installed — cannot manage containerd"
    SUDO="sudo"
}

# Prove sudo can actually authenticate BEFORE doing any real work.
#
# Without this the failure lands halfway through — typically after downloading
# a hundred megabytes, at the first install step — which wastes the download and
# points at the wrong thing. It also fails in any context with no controlling
# terminal for the password prompt, which is easy to hit when running through a
# tool that captures output.
ensure_sudo_auth() {
    [ -n "${SUDO}" ] || return 0
    sudo -n true 2>/dev/null && return 0
    log "root is required — sudo will prompt for your password"
    if ! sudo -v; then
        die "sudo could not authenticate.

If the message above says a terminal is required, there is no TTY for the
password prompt. Run this directly from a real terminal (your WSL shell or
Windows Terminal) rather than through a wrapper that captures output. You can
also pre-authenticate in that terminal with:

    sudo -v && ./scripts/dev/containerd-up.sh"
    fi
}

# The user the socket should belong to, so the JVM in :app:integrationTest can
# reach CRI without running the whole test suite as root. Under sudo, $SUDO_UID
# is the human who invoked it; otherwise it is just us.
invoking_uid() { echo "${SUDO_UID:-$(id -u)}"; }
invoking_gid() { echo "${SUDO_GID:-$(id -g)}"; }

# Is our instance actually up? Checks the socket rather than the PID, because
# the socket existing and accepting a connection is what callers care about.
containerd_running() {
    [ -S "${SOCKET}" ] || return 1
    local pid
    pid="$(read_pid)" || return 1
    [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null
}

# Read the PID of OUR containerd, verifying it really is ours before returning
# it. PIDs get reused; a stale pidfile must never cause us to signal an
# unrelated process.
read_pid() {
    local pid=""
    if have_systemd && systemctl is-active --quiet "${SYSTEMD_UNIT}" 2>/dev/null; then
        pid="$(systemctl show -p MainPID --value "${SYSTEMD_UNIT}" 2>/dev/null || true)"
        [ "${pid}" = "0" ] && pid=""
    fi
    if [ -z "${pid}" ] && [ -f "${PID_FILE}" ]; then
        pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
    fi
    [ -n "${pid}" ] || return 1
    [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
    # Confirm the process is a containerd running OUR config, not a PID reuse.
    if ! tr '\0' ' ' < "/proc/${pid}/cmdline" 2>/dev/null | grep -q "${CONFIG_FILE}"; then
        return 1
    fi
    echo "${pid}"
}
