#!/usr/bin/env bash
set -exuo pipefail

install_deps() {
  local attempt=0
  local max_attempts=${APK_MAX_RETRIES:-5}
  while true; do
    attempt=$((attempt + 1))
    if apk update && apk add --no-cache util-linux >/tmp/apk.log; then
      return 0
    fi
    if [[ ${attempt} -ge ${max_attempts} ]]; then
      echo "apk install failed after ${attempt} attempts" >&2
      sleep infinity
    fi
    sleep $((attempt * 2))
  done
}

install_deps

: "Materialize bundled flox build resources onto host filesystem"
HOST_ROOT="/proc/1/root"
install -D -m 0755 /build-assets/rke2lab-flox-build.sh "${HOST_ROOT}/srv/host/scripts.d/rke2lab-flox-build.sh"
install -D -m 0644 /build-assets/rke2lab-flox-build.yaml "${HOST_ROOT}/srv/host/scripts.d/rke2lab-flox-build.yaml"
mkdir -p "${HOST_ROOT}/srv/host/nix.d"
ln -sf ../scripts.d/rke2lab-flox-build.yaml "${HOST_ROOT}/srv/host/nix.d/flox-builds.yaml"

nsenter --target 1 --mount --uts --ipc --net --pid -- env \
  CONTAINERD_CONFIG_FILE="${CONTAINERD_CONFIG_FILE}" \
  bash <<'HOSTSCRIPT'
set -euxo pipefail

NIX_VAR="/nix/var/nix"
NIX_VAR_PROFILES_DEFAULT="${NIX_VAR}/profiles/default"

: "Ensure Nix is available in the host environment for the shim installer script"
source "${NIX_VAR_PROFILES_DEFAULT}/etc/profile.d/nix-daemon.sh"

: "Ensure the nix binary is available and executable"
NIX_BIN="${NIX_VAR_PROFILES_DEFAULT}/bin/nix"
[[ -x "${NIX_BIN}" ]] || {
  echo "Nix binary not found at ${NIX_BIN}" >&2
  exit 1
}
export NIX_BIN

: "Ensure the flox binary is available and executable"
FLOX_BIN="${NIX_VAR_PROFILES_DEFAULT}/bin/flox"
[[ -x "${FLOX_BIN}" ]] || {
  echo "Flox CLI not found at ${FLOX_BIN}" >&2
  exit 1
}
export FLOX_BIN

: "Add Nix and Flox to PATH for shim installer operations"
mkdir -p /usr/bin
ln -sf "${NIX_BIN}" /usr/bin/nix
ln -sf "${FLOX_BIN}" /usr/bin/flox

: "Ensure Nix and Flox binaries are on PATH for shim installer operations"
export PATH="${NIX_VAR_PROFILES_DEFAULT}/bin:${PATH}"

: "Additionally, ensure RKE2 bin directories are on PATH for shim installer operations"
export PATH="/var/lib/rancher/rke2/bin:/var/lib/rancher/rke2/agent/bin:${PATH}"

: "Set Flox environment variables to disable telemetry and interactivity for shim installation"
export FLOX_NO_TELEMETRY=1
export FLOX_NONINTERACTIVE=1

: "Run the shim installer script from the flox container environment (idempotent - will skip if already installed)"
CONFIG_FILE="${CONTAINERD_CONFIG_FILE}"
CONFIG_DIR="$(dirname "${CONFIG_FILE}")"
CONFIG_TEMPLATE="${CONFIG_DIR}/config.toml.tmpl"
FLOX_ENV_DIR="/var/lib/flox-runtime/containerd-shim"
ARCH="$(uname -m)"

mkdir -p "${FLOX_ENV_DIR}"
if [[ ! -d "${FLOX_ENV_DIR}/.flox" ]]; then
  (cd "${FLOX_ENV_DIR}" && flox init)
fi

if command -v containerd >/dev/null 2>&1; then
  CONTAINERD_BIN="$(command -v containerd)"
elif [[ -x /var/lib/rancher/rke2/bin/containerd ]]; then
  CONTAINERD_BIN="/var/lib/rancher/rke2/bin/containerd"
elif [[ -x /var/lib/rancher/rke2/agent/bin/containerd ]]; then
  CONTAINERD_BIN="/var/lib/rancher/rke2/agent/bin/containerd"
else
  echo "containerd binary not found" >&2
  exit 1
fi

CONTAINERD_VERSION="$(${CONTAINERD_BIN} --version | awk '{print $3}')"
CONTAINERD_VERSION="${CONTAINERD_VERSION#v}"
CONTAINERD_MAJOR="${CONTAINERD_VERSION%%.*}"
if [[ -z "${CONTAINERD_MAJOR}" ]]; then
  echo "unable to determine containerd version" >&2
  exit 1
fi
if [[ "${CONTAINERD_MAJOR}" -ge 2 ]]; then
  SHIM_PKG="flox/containerd-shim-flox-2x"
else
  SHIM_PKG="flox/containerd-shim-flox-17"
fi

flox install --dir "${FLOX_ENV_DIR}" "${SHIM_PKG}"

: "Ensure flox gcroots directory exists"
GCROOTS_DIR="${NIX_GC_ROOTS:-/nix/var/nix/gcroots}/flox"
GCROOTS_LINK="${GCROOTS_DIR}/system-profile"
mkdir -p "${GCROOTS_DIR}"
if [[ ! -e "${GCROOTS_LINK}" ]]; then
  ln -s /nix/var/nix/profiles/default "${GCROOTS_LINK}"
fi

SHIM_RUN_DIR="$(find "${FLOX_ENV_DIR}/.flox/run" -maxdepth 1 -name "${ARCH}-linux.containerd-shim*.run" -print -quit || true)"
if [[ -z "${SHIM_RUN_DIR}" ]]; then
  echo "unable to locate Flox shim run directory" >&2
  exit 1
fi
SHIM_PATH="$(realpath "${SHIM_RUN_DIR}")/bin/containerd-shim-flox-v2"
if [[ ! -f "${SHIM_PATH}" ]]; then
  echo "shim binary missing at ${SHIM_PATH}" >&2
  exit 1
fi
install -D -m 0755 "${SHIM_PATH}" /usr/local/bin/containerd-shim-flox-v2

if [[ ! -f "${CONFIG_TEMPLATE}" ]]; then
  cp "${CONFIG_FILE}" "${CONFIG_TEMPLATE}"
fi

CONFIG_VERSION="$(grep -m1 '^version' "${CONFIG_FILE}" | awk -F '=' '{print $2}' | tr -d ' "')"
if [[ "${CONFIG_VERSION}" == "3" ]]; then
  RUNTIME_SECTION='plugins."io.containerd.cri.v1.runtime".containerd.runtimes.flox'
else
  RUNTIME_SECTION='plugins."io.containerd.grpc.v1.cri".containerd.runtimes.flox'
fi

update_config() {
  local target="$1"
  [[ -f "${target}" ]] || return 0
  local tmp
  tmp="$(mktemp)"
  awk '
    BEGIN {skip=0}
    /^## Flox runtime shim/ {skip=1; next}
    /^\[plugins\..*containerd\.runtimes\.flox/ {skip=1; next}
    skip && /^\[/ && $0 !~ /containerd\.runtimes\.flox/ {skip=0}
    skip {next}
    {print}
  ' "${target}" > "${tmp}"
  mv "${tmp}" "${target}"
  cat <<EOF_BLOCK | sed "s|__RUNTIME_SECTION__|${RUNTIME_SECTION}|" | sed 's/^          //' >> "${target}"
## Flox runtime shim
[__RUNTIME_SECTION__]
  runtime_path = "/usr/local/bin/containerd-shim-flox-v2"
  runtime_type = "io.containerd.runc.v2"
  pod_annotations = [ "flox.dev/*" ]
  container_annotations = [ "flox.dev/*" ]
[__RUNTIME_SECTION__.options]
  SystemdCgroup = true
EOF_BLOCK
}

update_config "${CONFIG_FILE}"
update_config "${CONFIG_TEMPLATE}"

if systemctl is-active rke2-server >/dev/null; then
  systemctl restart rke2-server
elif systemctl is-active rke2-agent >/dev/null; then
  systemctl restart rke2-agent
elif systemctl is-active containerd >/dev/null; then
  systemctl restart containerd
else
  echo "no known service to restart" >&2
fi
HOSTSCRIPT
