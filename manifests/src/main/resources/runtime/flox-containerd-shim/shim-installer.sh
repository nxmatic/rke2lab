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
install -D -m 0755 /build-assets/flox-shim-build.sh "${HOST_ROOT}/srv/host/flox-shim.d/flox-shim-build.sh"
install -D -m 0644 /build-assets/flox-shim-build.yaml "${HOST_ROOT}/srv/host/flox-shim.d/flox-shim-build.yaml"
install -D -m 0644 /build-assets/packages-mesh-headplane-flake.nix "${HOST_ROOT}/srv/host/flox-shim.d/packages/mesh/headplane/flake.nix"
install -D -m 0644 /build-assets/packages-networking-kdns-flake.nix "${HOST_ROOT}/srv/host/flox-shim.d/packages/networking/kdns/flake.nix"

nsenter --target 1 --mount --uts --ipc --net --pid -- env \
  CONTAINERD_CONFIG_FILE="${CONTAINERD_CONFIG_FILE}" \
  bash -s -- < /scripts/shim-installer-host.sh
