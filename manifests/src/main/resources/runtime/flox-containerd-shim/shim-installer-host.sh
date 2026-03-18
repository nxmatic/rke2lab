#!/usr/bin/env bash
set -euxo pipefail

DAEMONSET_SCRIPT_ROOT="${DAEMONSET_SCRIPT_ROOT:-/srv/host/k8s-daemonset.d/runtime/flox-containerd-shim}"

# shellcheck disable=SC1091
source "${DAEMONSET_SCRIPT_ROOT}/.sh.d/daemonset-logging.sh"
daemonset::logging:stderr:setup "${DAEMONSET_SCRIPT_ROOT}/shim-installer-host.sh"

# shellcheck disable=SC1090
source <(flox activate --dir /var/lib/rancher/rke2)

NIX_VAR="/nix/var/nix"
NIX_VAR_PROFILES_DEFAULT="${NIX_VAR}/profiles/default"

NIX_BIN=""
FLOX_BIN=""
GIT_BIN=""
FLOX_SHIM_ROOT=""
FLOX_BUILD_SCRIPT=""
FLOX_BUILD_DESCRIPTOR=""
FLOX_SHIM_MESH_DIR=""
FLOX_SHIM_NETWORKING_DIR=""

host::tooling:init() {
  : "Ensure Nix is available in the host environment for shim installer operations"
  # shellcheck disable=SC1091
  source "${NIX_VAR_PROFILES_DEFAULT}/etc/profile.d/nix-daemon.sh"

  NIX_BIN="${NIX_VAR_PROFILES_DEFAULT}/bin/nix"
  [[ -x "${NIX_BIN}" ]] || {
    echo "Nix binary not found at ${NIX_BIN}" >&2
    exit 1
  }
  export NIX_BIN

  FLOX_BIN="${NIX_VAR_PROFILES_DEFAULT}/bin/flox"
  [[ -x "${FLOX_BIN}" ]] || {
    echo "Flox CLI not found at ${FLOX_BIN}" >&2
    exit 1
  }
  export FLOX_BIN

  GIT_BIN="${NIX_VAR_PROFILES_DEFAULT}/bin/git"
  [[ -x "${GIT_BIN}" ]] || {
    echo "Git binary not found at ${GIT_BIN}" >&2
    exit 1
  }
  export GIT_BIN

  mkdir -p /usr/bin
  ln -sf "${NIX_BIN}" /usr/bin/nix
  ln -sf "${FLOX_BIN}" /usr/bin/flox
  ln -sf "${GIT_BIN}" /usr/bin/git

  export PATH="${NIX_VAR_PROFILES_DEFAULT}/bin:${PATH}"
  export PATH="/var/lib/rancher/rke2/bin:/var/lib/rancher/rke2/agent/bin:${PATH}"

  export FLOX_NO_TELEMETRY=1
  export FLOX_NONINTERACTIVE=1
}

shim::assets:path:init() {
  FLOX_SHIM_ROOT="/srv/host/k8s-daemonset.d/runtime/flox-containerd-shim"
  FLOX_BUILD_SCRIPT="${FLOX_SHIM_ROOT}/flox-shim-build.sh"
  FLOX_BUILD_DESCRIPTOR="${FLOX_SHIM_ROOT}/flox-shim-build.yaml"
  FLOX_SHIM_MESH_DIR="${FLOX_SHIM_ROOT}/mesh"
  FLOX_SHIM_NETWORKING_DIR="${FLOX_SHIM_ROOT}/networking"
}

shim::assets:path:validate() {
  [[ -x "${FLOX_BUILD_SCRIPT}" ]] || {
    echo "flox build script missing or not executable: ${FLOX_BUILD_SCRIPT}" >&2
    exit 1
  }
  [[ -r "${FLOX_BUILD_DESCRIPTOR}" ]] || {
    echo "flox build descriptor missing or unreadable: ${FLOX_BUILD_DESCRIPTOR}" >&2
    exit 1
  }
  [[ -d "${FLOX_SHIM_MESH_DIR}" ]] || {
    echo "flox shim mesh directory missing: ${FLOX_SHIM_MESH_DIR}" >&2
    exit 1
  }
  [[ -d "${FLOX_SHIM_NETWORKING_DIR}" ]] || {
    echo "flox shim networking directory missing: ${FLOX_SHIM_NETWORKING_DIR}" >&2
    exit 1
  }
}

containerd::config:path:resolve() {
  local configured="${CONTAINERD_CONFIG_FILE:-}"
  local candidate

  for candidate in \
    "${configured}" \
    "/var/lib/rancher/rke2/agent/etc/containerd/config.toml" \
    "/var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml"; do
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

  printf '%s\n' "/var/lib/rancher/rke2/agent/etc/containerd/config.toml"
}

CONFIG=""
CONFIG_DIR=""
CONFIG_BASENAME=""
CONFIG_TEMPLATE=""

containerd::config:path:init() {
  CONFIG="$(containerd::config:path:resolve)"
  CONFIG_DIR="$(dirname "${CONFIG}")"
  CONFIG_BASENAME="$(basename "${CONFIG}")"
  if [[ "${CONFIG_BASENAME}" == "config-v3.toml" ]]; then
    CONFIG_TEMPLATE="${CONFIG_DIR}/config-v3.toml.tmpl"
  else
    CONFIG_TEMPLATE="${CONFIG_DIR}/config.toml.tmpl"
  fi
}

#
# Define functions for shim installation operations
#

container::service:runtime:restart() {
  if systemctl is-active rke2-server >/dev/null; then
    systemctl restart rke2-server
  elif systemctl is-active rke2-agent >/dev/null; then
    systemctl restart rke2-agent
  elif systemctl is-active containerd >/dev/null; then
    systemctl restart containerd
  else
    echo "no known service to restart" >&2
  fi
}

shim::assets:build:run() {
  : "Ensure we have a git repository in the flox shim root for build operations, and set a default user if not already configured"
  if [[ ! -d "${FLOX_SHIM_ROOT}/.git" ]]; then
    git -C "${FLOX_SHIM_ROOT}" init --initial-branch=main
  fi

  if [[ -z "$(git -C "${FLOX_SHIM_ROOT}" config --get user.name || true)" ]]; then
    git -C "${FLOX_SHIM_ROOT}" config user.name "rke2lab-flox-shim"
  fi
  if [[ -z "$(git -C "${FLOX_SHIM_ROOT}" config --get user.email || true)" ]]; then
    git -C "${FLOX_SHIM_ROOT}" config user.email "rke2lab-flox-shim@localhost"
  fi

  : "Run the flox build script to materialize the shim build output onto the host filesystem for use in installation"
  "${FLOX_BUILD_SCRIPT}" "host" "${FLOX_BUILD_DESCRIPTOR}"

  : "Commit any changes to the flox shim build assets to the git repository for tracking"
  git -C "${FLOX_SHIM_ROOT}" add mesh networking flox-shim-build.yaml flox-shim-build.sh
  if ! git -C "${FLOX_SHIM_ROOT}" diff --cached --quiet; then
    git -C "${FLOX_SHIM_ROOT}" commit -m "chore(flox-shim): refresh packaged flakes"
  fi
}

shim::runtime:containerd:resolve-bin() {
  if command -v containerd >/dev/null 2>&1; then
    command -v containerd
    return 0
  fi
  if [[ -x /var/lib/rancher/rke2/bin/containerd ]]; then
    printf '%s\n' "/var/lib/rancher/rke2/bin/containerd"
    return 0
  fi
  if [[ -x /var/lib/rancher/rke2/agent/bin/containerd ]]; then
    printf '%s\n' "/var/lib/rancher/rke2/agent/bin/containerd"
    return 0
  fi

  echo "containerd binary not found" >&2
  return 1
}

shim::runtime:package:resolve() {
  local containerd_bin="$1"
  local containerd_version containerd_major

  read -r _ _ containerd_version _ < <("${containerd_bin}" --version)
  containerd_version="${containerd_version#v}"
  containerd_major="${containerd_version%%.*}"
  if [[ -z "${containerd_major}" ]]; then
    echo "unable to determine containerd version" >&2
    return 1
  fi

  if [[ "${containerd_major}" -ge 2 ]]; then
    printf '%s\n' "flox/containerd-shim-flox-2x"
  else
    printf '%s\n' "flox/containerd-shim-flox-17"
  fi
}

shim::runtime:env:ensure() {
  local flox_env_dir="$1"

  mkdir -p "${CONFIG_DIR}"
  mkdir -p "${flox_env_dir}"
  if [[ ! -d "${flox_env_dir}/.flox" ]]; then
    (cd "${flox_env_dir}" && flox init)
  fi
}

shim::runtime:gcroots:ensure() {
  local gcroots_dir gcroots_link

  gcroots_dir="${NIX_GC_ROOTS:-/nix/var/nix/gcroots}/flox"
  gcroots_link="${gcroots_dir}/system-profile"
  mkdir -p "${gcroots_dir}"
  if [[ ! -e "${gcroots_link}" ]]; then
    ln -s /nix/var/nix/profiles/default "${gcroots_link}"
  fi
}

shim::runtime:binary:install() {
  local flox_env_dir="$1"
  local arch="$2"
  local shim_run_dir shim_path

  shim_run_dir="$(find "${flox_env_dir}/.flox/run" -maxdepth 1 -name "${arch}-linux.containerd-shim*.run" -print -quit || true)"
  if [[ -z "${shim_run_dir}" ]]; then
    echo "unable to locate Flox shim run directory" >&2
    return 1
  fi

  shim_path="$(realpath "${shim_run_dir}")/bin/containerd-shim-flox-v2"
  if [[ ! -f "${shim_path}" ]]; then
    echo "shim binary missing at ${shim_path}" >&2
    return 1
  fi

  install -D -m 0755 "${shim_path}" /usr/local/bin/containerd-shim-flox-v2
}

shim::runtime:config-template:ensure() {
  if [[ ! -f "${CONFIG_TEMPLATE}" ]]; then
    cp "${CONFIG}" "${CONFIG_TEMPLATE}"
  fi
}

shim::runtime:core:install() {
  : "Install/refresh flox runtime shim binaries on host"
  local flox_env_dir arch containerd_bin shim_pkg

  flox_env_dir="/var/lib/flox-runtime/containerd-shim"
  arch="$(uname -m)"

  shim::runtime:env:ensure "${flox_env_dir}"
  containerd_bin="$(shim::runtime:containerd:resolve-bin)"
  shim_pkg="$(shim::runtime:package:resolve "${containerd_bin}")"

  flox install --dir "${flox_env_dir}" "${shim_pkg}"
  shim::runtime:gcroots:ensure
  shim::runtime:binary:install "${flox_env_dir}" "${arch}"
  shim::runtime:config-template:ensure
}

PREAMBLE=''

config::format:yaml:to() {
  local file="${1:-/dev/stdin}"
  local head
  head="$(head -n 1 "${file}" || true)"

  PREAMBLE=""
  if [[ "${head}" =~ \{\{.*\}\} ]]; then
    PREAMBLE="${head}"
    tail -n +2 "${file}" | dasel -r toml -w yaml '.'
  else
    dasel -r toml -w yaml -f "${file}" '.'
  fi
}

config::format:yaml:from() {
    local file="${1:-/dev/stdin}"
    [[ -n "${PREAMBLE}" ]] && echo "${PREAMBLE}"
    dasel -i yaml -o toml < "${file}"
}

containerd::config:version:detect() {
  local config="$1"
  local version
  version="$(config::format:yaml:to "${config}" | yq -r '.version // ""' 2>/dev/null || true)"

  if [[ -z "${version}" ]]; then
    case "$(basename "${config}")" in
      config-v3.toml|config-v3.toml.tmpl)
        version="3"
        ;;
      *)
        version="2"
        ;;
    esac
  fi

  printf '%s\n' "${version}"
}

containerd::config:flox:update() {
  local target="$1"
  [[ -f "${target}" ]] || return 0
  local version
  version="$(containerd::config:version:detect "${target}")"
  local plugin_root tmp
  if [[ "${version}" == "3" ]]; then
    plugin_root="io.containerd.cri.v1.runtime"
  else
    plugin_root="io.containerd.grpc.v1.cri"
  fi
  tmp="$(mktemp)"

  config::format:yaml:to "${target}" |
    CRI_PLUGIN_ROOT="${plugin_root}" yq '
      del(.plugins."io.containerd.cri.v1.runtime".containerd.runtimes.flox) |
      del(.plugins."io.containerd.grpc.v1.cri".containerd.runtimes.flox) |
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes.flox.runtime_path = "/usr/local/bin/containerd-shim-flox-v2" |
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes.flox.runtime_type = "io.containerd.runc.v2" |
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes.flox.pod_annotations = ["flox.dev/*"] |
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes.flox.container_annotations = ["flox.dev/*"] |
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes.flox.options.SystemdCgroup = true
    ' |
    config::format:yaml:from > "${tmp}"
  mv "${tmp}" "${target}"
}

: "Initialize host tooling and shim asset paths"
host::tooling:init
shim::assets:path:init
shim::assets:path:validate

: "Initialize resolved containerd config paths"
containerd::config:path:init

: "Install/update flox runtime shim binaries on host"
shim::runtime:core:install

: "Execute the shim build before mutating containerd config"
shim::assets:build:run

: "Update containerd configuration to include the flox shim runtime"
containerd::config:flox:update "${CONFIG}"
containerd::config:flox:update "${CONFIG_TEMPLATE}"

: "Restart containerd to apply shim installation changes"
container::service:runtime:restart
