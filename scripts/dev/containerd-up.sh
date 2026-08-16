#!/usr/bin/env bash
# Start this project's dedicated containerd, installing it first if missing.
#
# Target: Ubuntu on WSL2. Works with or without systemd — WSL2 only has systemd
# if /etc/wsl.conf sets [boot] systemd=true, so both paths are real here.
#
# This instance is entirely separate from any system containerd and from Docker
# Desktop's. It has its own socket, root, state, config, and CNI network, all
# namespaced under "mcorch-dev". Nothing here reads or writes /run/containerd,
# /var/lib/containerd, or any Docker socket.
#
# Safe to re-run: if the instance is already up, this reports and exits 0.
#
#   ./scripts/dev/containerd-up.sh              install if needed, then start
#   ./scripts/dev/containerd-up.sh --no-systemd force the direct-daemon path
#   ./scripts/dev/containerd-up.sh --skip-install  fail instead of installing

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/dev/containerd-env.sh
. "${SCRIPT_DIR}/containerd-env.sh"

USE_SYSTEMD="auto"
SKIP_INSTALL=0

while [ $# -gt 0 ]; do
    case "$1" in
        --no-systemd)   USE_SYSTEMD="no" ;;
        --skip-install) SKIP_INSTALL=1 ;;
        -h|--help)      sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)              die "unknown argument: $1 (try --help)" ;;
    esac
    shift
done

# ─── preflight ───────────────────────────────────────────────────────────────

[ "$(uname -s)" = "Linux" ] || die "this script only supports Linux (found $(uname -s))"
[ "$(uname -m)" = "x86_64" ] || die "only linux/amd64 is pinned here (found $(uname -m)); add the matching checksums to containerd-env.sh"

is_wsl || warn "this does not look like WSL — the script should still work, but it is only exercised on WSL2 Ubuntu"

if containerd_running; then
    log "already running: ${SOCKET} (pid $(read_pid))"
    log "CRI endpoint: ${CRI_ENDPOINT}"
    exit 0
fi

# Establish root up front. Everything past this point needs it, so failing here
# costs nothing, whereas failing later wastes a large download.
resolve_sudo
ensure_sudo_auth

# Stale socket from an unclean shutdown would make containerd fail to bind.
if [ -S "${SOCKET}" ] && ! containerd_running; then
    warn "stale socket at ${SOCKET} with no live process — removing it"
    ${SUDO} rm -f "${SOCKET}" "${TTRPC_SOCKET}" "${PID_FILE}"
fi

# ─── install ─────────────────────────────────────────────────────────────────

TMP_DIR=""
cleanup() { [ -n "${TMP_DIR}" ] && rm -rf "${TMP_DIR}"; }
trap cleanup EXIT

tmpdir() {
    [ -n "${TMP_DIR}" ] || TMP_DIR="$(mktemp -d)"
    echo "${TMP_DIR}"
}

# Download and verify against a pinned checksum. A mismatch aborts — we are
# about to install this as root, so "close enough" is not a thing.
fetch_verified() {
    local url="$1" want="$2" dest="$3" got
    log "downloading ${url##*/}"
    curl -fsSL --max-time 600 -o "${dest}" "${url}" \
        || die "download failed: ${url}"
    got="$(sha256sum "${dest}" | cut -d' ' -f1)"
    if [ "${got}" != "${want}" ]; then
        die "checksum mismatch for ${url##*/}
  expected ${want}
  actual   ${got}
Refusing to install. If upstream legitimately re-published this asset, update
the pinned checksum in scripts/dev/containerd-env.sh after verifying it yourself."
    fi
}

installed_version() { "$1" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1; }

# Takes the path this script would install to, not a name to look up.
#
# A PATH lookup answers "is there a containerd on this machine", which is not
# the question. This instance runs the binary at its own pinned path and nothing
# else; a host that already has one somewhere on PATH — every GitHub runner does,
# because Docker ships it — would satisfy a PATH check, skip the install, and
# leave the pinned path empty for the launch below to fail on. That is not
# hypothetical: it is how CI first failed here, with `installing: runc crictl
# cni-plugins` and then `/usr/local/bin/containerd: command not found`.
#
# WSL2 never has containerd pre-installed, so the two questions had the same
# answer everywhere this had been run until then.
need_install() {
    local path="$1" want="$2"
    [ -x "${path}" ] || return 0
    [ "$(installed_version "${path}")" = "${want}" ] || return 0
    return 1
}

install_containerd() {
    local t; t="$(tmpdir)"
    fetch_verified \
        "https://github.com/containerd/containerd/releases/download/v${CONTAINERD_VERSION}/containerd-${CONTAINERD_VERSION}-linux-amd64.tar.gz" \
        "${CONTAINERD_SHA256}" "${t}/containerd.tgz"
    tar -xzf "${t}/containerd.tgz" -C "${t}"
    ${SUDO} install -m 0755 -t "${INSTALL_BIN_DIR}" "${t}"/bin/*
    log "installed containerd ${CONTAINERD_VERSION} -> ${INSTALL_BIN_DIR}"
}

install_runc() {
    local t; t="$(tmpdir)"
    fetch_verified \
        "https://github.com/opencontainers/runc/releases/download/v${RUNC_VERSION}/runc.amd64" \
        "${RUNC_SHA256}" "${t}/runc"
    # /usr/local/sbin is on systemd's default PATH, which is where the runc shim
    # looks for it.
    ${SUDO} install -m 0755 "${t}/runc" "${INSTALL_SBIN_DIR}/runc"
    log "installed runc ${RUNC_VERSION} -> ${INSTALL_SBIN_DIR}/runc"
}

install_cni_plugins() {
    local t; t="$(tmpdir)"
    fetch_verified \
        "https://github.com/containernetworking/plugins/releases/download/v${CNI_PLUGINS_VERSION}/cni-plugins-linux-amd64-v${CNI_PLUGINS_VERSION}.tgz" \
        "${CNI_PLUGINS_SHA256}" "${t}/cni.tgz"
    ${SUDO} mkdir -p "${CNI_BIN_DIR}"
    ${SUDO} tar -xzf "${t}/cni.tgz" -C "${CNI_BIN_DIR}"
    echo "${CNI_PLUGINS_VERSION}" | ${SUDO} tee "${CNI_STAMP}" >/dev/null
    log "installed cni-plugins ${CNI_PLUGINS_VERSION} -> ${CNI_BIN_DIR}"
}

install_crictl() {
    local t; t="$(tmpdir)"
    fetch_verified \
        "https://github.com/kubernetes-sigs/cri-tools/releases/download/v${CRICTL_VERSION}/crictl-v${CRICTL_VERSION}-linux-amd64.tar.gz" \
        "${CRICTL_SHA256}" "${t}/crictl.tgz"
    tar -xzf "${t}/crictl.tgz" -C "${t}"
    ${SUDO} install -m 0755 "${t}/crictl" "${INSTALL_BIN_DIR}/crictl"
    log "installed crictl ${CRICTL_VERSION} -> ${INSTALL_BIN_DIR}/crictl"
}

want=()
need_install "${INSTALL_BIN_DIR}/containerd" "${CONTAINERD_VERSION}" && want+=(containerd)
need_install "${INSTALL_SBIN_DIR}/runc" "${RUNC_VERSION}" && want+=(runc)
need_install "${INSTALL_BIN_DIR}/crictl" "${CRICTL_VERSION}" && want+=(crictl)
[ "$(cat "${CNI_STAMP}" 2>/dev/null || true)" = "${CNI_PLUGINS_VERSION}" ] || want+=(cni-plugins)

if [ ${#want[@]} -gt 0 ]; then
    if [ "${SKIP_INSTALL}" -eq 1 ]; then
        die "--skip-install given but these are missing or the wrong version: ${want[*]}"
    fi
    log "installing: ${want[*]}"
    for c in "${want[@]}"; do
        case "${c}" in
            containerd)  install_containerd ;;
            runc)        install_runc ;;
            crictl)      install_crictl ;;
            cni-plugins) install_cni_plugins ;;
        esac
    done
else
    log "all components already present at the pinned versions"
fi

# ─── configuration ───────────────────────────────────────────────────────────

# The cgroup driver has to match how the system actually manages cgroups. With
# systemd + cgroup v2 the systemd driver is correct; without systemd there is
# nothing to delegate to, so runc manages cgroups directly.
if have_systemd && cgroup_v2; then
    SYSTEMD_CGROUP="true"
else
    SYSTEMD_CGROUP="false"
fi

uid="$(invoking_uid)"
gid="$(invoking_gid)"

log "writing ${CONFIG_FILE}"
${SUDO} mkdir -p "${CONF_DIR}" "${CNI_CONF_DIR}" "${RUN_DIR}" "${STATE_DIR}" "${ROOT_DIR}"

# config version 4 is what containerd 2.3.x emits and expects. Note the CRI
# settings live under io.containerd.cri.v1.runtime — NOT io.containerd.grpc.v1.cri,
# which in 2.x holds only the exec/attach streaming server. Putting runtime or
# CNI settings under the old id parses fine and is then silently ignored, which
# shows up much later as sandboxes that will not start.
${SUDO} tee "${CONFIG_FILE}" >/dev/null <<EOF
# Generated by scripts/dev/containerd-up.sh — edits here are overwritten.
# Dedicated dev instance for mc-server-orchestrator. Not the system containerd.
version = 4

root  = '${ROOT_DIR}'
state = '${STATE_DIR}'

[plugins.'io.containerd.server.v1.grpc']
  address = '${SOCKET}'
  # Own the socket as the invoking user so the JVM integration tests can reach
  # CRI without running the whole suite as root.
  uid = ${uid}
  gid = ${gid}

[plugins.'io.containerd.server.v1.ttrpc']
  address = '${TTRPC_SOCKET}'

[plugins.'io.containerd.cri.v1.runtime']

  [plugins.'io.containerd.cri.v1.runtime'.containerd]
    default_runtime_name = 'runc'

    [plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runc]
      runtime_type = 'io.containerd.runc.v2'

      [plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runc.options]
        SystemdCgroup = ${SYSTEMD_CGROUP}

  [plugins.'io.containerd.cri.v1.runtime'.cni]
    bin_dirs = ['${CNI_BIN_DIR}']
    # A private conf dir, so a system CNI setup in /etc/cni/net.d is neither
    # read nor disturbed.
    conf_dir = '${CNI_CONF_DIR}'
    # Handle the pod loopback interface internally rather than requiring a
    # separate 99-loopback.conf on disk.
    use_internal_loopback = true
EOF

# CRI cannot start a pod sandbox without a usable CNI network. This is the most
# common reason a fresh containerd accepts connections but fails RunPodSandbox.
# 10.87/16 is chosen to avoid the 10.88/16 that stock containerd setups use.
log "writing ${CNI_CONF_DIR}/10-${INSTANCE}.conflist"
${SUDO} tee "${CNI_CONF_DIR}/10-${INSTANCE}.conflist" >/dev/null <<EOF
{
  "cniVersion": "1.0.0",
  "name": "${INSTANCE}",
  "plugins": [
    {
      "type": "bridge",
      "bridge": "${INSTANCE%%-*}0",
      "isGateway": true,
      "ipMasq": true,
      "hairpinMode": true,
      "ipam": {
        "type": "host-local",
        "ranges": [[{ "subnet": "10.87.0.0/16" }]],
        "routes": [{ "dst": "0.0.0.0/0" }]
      }
    },
    {
      "type": "portmap",
      "capabilities": { "portMappings": true }
    }
  ]
}
EOF

# Validate the config before starting, so a bad edit fails here with a clear
# message rather than as a daemon that exits immediately.
${SUDO} "${INSTALL_BIN_DIR}/containerd" -c "${CONFIG_FILE}" config dump >/dev/null \
    || die "containerd rejected ${CONFIG_FILE}"

# ─── start ───────────────────────────────────────────────────────────────────

start_with_systemd() {
    log "starting via systemd unit ${SYSTEMD_UNIT}"
    ${SUDO} tee "${SYSTEMD_UNIT_FILE}" >/dev/null <<EOF
# Generated by scripts/dev/containerd-up.sh — edits here are overwritten.
[Unit]
Description=containerd (mc-server-orchestrator dev instance)
Documentation=https://containerd.io
After=network.target

[Service]
Type=notify
ExecStart=${INSTALL_BIN_DIR}/containerd --config ${CONFIG_FILE}
Restart=no
Delegate=yes
KillMode=process
OOMScoreAdjust=-999
LimitNOFILE=infinity
TasksMax=infinity

[Install]
WantedBy=multi-user.target
EOF
    ${SUDO} systemctl daemon-reload
    # Deliberately not `enable` — this is a dev instance you start when you want
    # it, not something that comes up with the distro.
    ${SUDO} systemctl start "${SYSTEMD_UNIT}"
}

start_directly() {
    log "starting containerd directly (no systemd)"
    ${SUDO} sh -c "setsid '${INSTALL_BIN_DIR}/containerd' --config '${CONFIG_FILE}' --log-level info \
        >>'${LOG_FILE}' 2>&1 </dev/null & echo \$! >'${PID_FILE}'"
}

if [ "${USE_SYSTEMD}" = "no" ]; then
    have_systemd && log "systemd is available but --no-systemd was given"
    start_directly
elif have_systemd; then
    start_with_systemd
else
    log "systemd not running in this distro (no /run/systemd/system)"
    log "  to enable it: put '[boot]\\nsystemd=true' in /etc/wsl.conf, then 'wsl --shutdown' from Windows"
    start_directly
fi

# ─── verify ──────────────────────────────────────────────────────────────────

log "waiting for ${SOCKET}"
for _ in $(seq 1 100); do
    [ -S "${SOCKET}" ] && break
    sleep 0.2
done

if [ ! -S "${SOCKET}" ]; then
    warn "socket did not appear within 20s"
    if have_systemd && [ "${USE_SYSTEMD}" != "no" ]; then
        ${SUDO} systemctl status --no-pager --lines=20 "${SYSTEMD_UNIT}" >&2 || true
    else
        ${SUDO} tail -n 20 "${LOG_FILE}" >&2 || true
    fi
    die "containerd failed to start"
fi

# The socket existing is not proof CRI is serving on it. Ask crictl.
if ! out="$(crictl --runtime-endpoint "${CRI_ENDPOINT}" version 2>&1)"; then
    warn "socket is up but CRI did not answer:"
    printf '%s\n' "${out}" >&2
    die "containerd is running but its CRI service is not healthy"
fi

log "containerd ${CONTAINERD_VERSION} is up (pid $(read_pid))"
printf '%s\n' "${out}" | sed 's/^/    /' >&2

cat >&2 <<EOF

  CRI endpoint : ${CRI_ENDPOINT}
  config       : ${CONFIG_FILE}
  root / state : ${ROOT_DIR} , ${STATE_DIR}
  logs         : $(if have_systemd && [ "${USE_SYSTEMD}" != "no" ]; then echo "journalctl -u ${SYSTEMD_UNIT} -f"; else echo "${LOG_FILE}"; fi)

  Point tooling at it without touching any global config:
    export CONTAINER_RUNTIME_ENDPOINT=${CRI_ENDPOINT}
    crictl version

  Stop it with:
    ./scripts/dev/containerd-down.sh
EOF
