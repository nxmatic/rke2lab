#!/usr/bin/env -S bash -exu -o pipefail

: "Activate RKE2 flox environment (provides cachix command)"
set +x # Silence flox activation noise
source <(flox activate --dir=/var/lib/rancher/rke2)
set -x

log() {
    printf '[cachix-watch-store] %s\n' "$*" >&2
}

: "Verify prerequisites"
if [[ -z "${CACHIX_AUTH_TOKEN:-}" ]]; then
    log "ERROR: CACHIX_AUTH_TOKEN not set; check bootstrap-env secrets loading"
    exit 1
fi

if ! command -v cachix >/dev/null 2>&1; then
    log "ERROR: cachix command not found; check RKE2 flox environment"
    exit 1
fi

: "Configuration"
CACHE_NAME="${RKE2LAB_CACHIX_CACHE_NAME:-nxmatic}"
JOBS="${RKE2LAB_CACHIX_WATCH_STORE_JOBS:-4}"
COMPRESSION_LEVEL="${RKE2LAB_CACHIX_COMPRESSION_LEVEL:-}"

: "Build cachix watch-store command"
watch_store_args=(
    "watch-store"
    "${CACHE_NAME}"
    "--jobs" "${JOBS}"
)

if [[ -n "${COMPRESSION_LEVEL}" ]]; then
    watch_store_args+=("--compression-level" "${COMPRESSION_LEVEL}")
fi

log "Starting cachix watch-store for ${CACHE_NAME} (${JOBS} workers)"
exec cachix "${watch_store_args[@]}"
