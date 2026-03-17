#!/usr/bin/env bash
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

: "Run flox package prebuild jobs during shim installer init"
FLOX_BUILD_SCRIPT="/srv/host/systemd-scripts.d/rke2lab-flox-build.sh"
FLOX_BUILD_DESCRIPTOR="/srv/host/systemd-scripts.d/rke2lab-flox-build.yaml"
[[ -x "${FLOX_BUILD_SCRIPT}" ]] || {
  echo "flox build script missing or not executable: ${FLOX_BUILD_SCRIPT}" >&2
  exit 1
}
[[ -r "${FLOX_BUILD_DESCRIPTOR}" ]] || {
  echo "flox build descriptor missing or unreadable: ${FLOX_BUILD_DESCRIPTOR}" >&2
  exit 1
}
"${FLOX_BUILD_SCRIPT}" "${FLOX_BUILD_DESCRIPTOR}"

: "Resolve containerd config and template files"
resolve_containerd_config() {
  local configured="${CONTAINERD_CONFIG_FILE:-}"
  local candidate

  for candidate in \
    "${configured}" \
    "/var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml" \
    "/var/lib/rancher/rke2/agent/etc/containerd/config.toml"; do
    [[ -n "${candidate}" ]] || continue
    if [[ -f "${candidate}" ]]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done

  if [[ -n "${configured}" ]]; then
    printf '%s\n' "${configured}"
    return 0
  fi

  printf '%s\n' "/var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml"
}

CONFIG_FILE="$(resolve_containerd_config)"
CONFIG_DIR="$(dirname "${CONFIG_FILE}")"
CONFIG_BASENAME="$(basename "${CONFIG_FILE}")"
if [[ "${CONFIG_BASENAME}" == "config-v3.toml" ]]; then
  CONFIG_TEMPLATE="${CONFIG_DIR}/config-v3.toml.tmpl"
else
  CONFIG_TEMPLATE="${CONFIG_DIR}/config.toml.tmpl"
fi

: "Run the shim installer script from the flox container environment"
FLOX_ENV_DIR="/var/lib/flox-runtime/containerd-shim"
ARCH="$(uname -m)"

mkdir -p "${CONFIG_DIR}"

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
