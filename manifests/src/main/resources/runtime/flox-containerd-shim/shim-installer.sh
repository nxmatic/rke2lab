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
HOST_ROOT="${HOST_ROOT:-/host-root}"
SCRIPT_MOUNT_DIR="${SCRIPT_MOUNT_DIR:-/scripts}"
SCRIPT_POLICY_DIR="${SCRIPT_POLICY_DIR:-/runtime-daemonset}"
BUILD_ASSETS_DIR="${BUILD_ASSETS_DIR:-/build-assets}"
DAEMONSET_ASSET_ROOT="/srv/host/k8s-daemonset.d/runtime/flox-containerd-shim"
HOST_SCRIPT_ROOT="${HOST_ROOT}${DAEMONSET_ASSET_ROOT}"

mkdir -p "${HOST_SCRIPT_ROOT}"

install -D -m 0755 "${SCRIPT_MOUNT_DIR}/shim-installer.sh" "${HOST_SCRIPT_ROOT}/shim-installer.sh"
install -D -m 0755 "${SCRIPT_MOUNT_DIR}/shim-installer-host.sh" "${HOST_SCRIPT_ROOT}/shim-installer-host.sh"
install -D -m 0644 "${SCRIPT_POLICY_DIR}/daemonset-logging.sh" "${HOST_SCRIPT_ROOT}/.sh.d/daemonset-logging.sh"

# shellcheck disable=SC1091
source "${SCRIPT_POLICY_DIR}/daemonset-logging.sh"
daemonset::logging:stderr:setup "${HOST_SCRIPT_ROOT}/shim-installer.sh"

install -D -m 0755 "${BUILD_ASSETS_DIR}/flox-shim-build.sh" "${HOST_SCRIPT_ROOT}/flox-shim-build.sh"
install -D -m 0644 "${BUILD_ASSETS_DIR}/flox-shim-build.yaml" "${HOST_SCRIPT_ROOT}/flox-shim-build.yaml"
install -D -m 0644 "${BUILD_ASSETS_DIR}/mesh/headplane/flake.nix" "${HOST_SCRIPT_ROOT}/mesh/headplane/flake.nix"
install -D -m 0644 "${BUILD_ASSETS_DIR}/networking/kdns/flake.nix" "${HOST_SCRIPT_ROOT}/networking/kdns/flake.nix"

nsenter --target 1 --mount --uts --ipc --net --pid -- env \
  CONTAINERD_CONFIG_FILE="${CONTAINERD_CONFIG_FILE}" \
  DAEMONSET_SCRIPT_ROOT="${DAEMONSET_ASSET_ROOT}" \
  bash -x "${DAEMONSET_ASSET_ROOT}/shim-installer-host.sh"
