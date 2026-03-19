#!/usr/bin/env bash
set -euo pipefail

REAL_SHIM="${FLOX_SHIM_REAL:-/usr/local/libexec/rke2lab/flox-shim-wrapper/containerd-shim-flox-v2.real}"
ROOTFS_SYNC_HELPER="${FLOX_ROOTFS_SYNC_HELPER:-/usr/local/libexec/rke2lab/flox-shim-wrapper/flox-rootfs-sync.sh}"
ROOTFS_SYNC_ENABLED="${FLOX_SHIM_ROOTFS_SYNC:-0}"
WRAPPER_LOG="${FLOX_SHIM_WRAPPER_LOG:-/var/log/rke2lab/flox-shim-wrapper.log}"

ensure_log_target() {
  local log_dir

  log_dir="$(dirname -- "${WRAPPER_LOG}")"
  if mkdir -p "${log_dir}" 2>/dev/null; then
    : >>"${WRAPPER_LOG}" 2>/dev/null || true
    return 0
  fi

  WRAPPER_LOG="/tmp/flox-shim-wrapper.log"
  : >>"${WRAPPER_LOG}" 2>/dev/null || true
}

ensure_log_target

log() {
  local ts

  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '[%s] %s\n' "${ts}" "$*" >>"${WRAPPER_LOG}" 2>/dev/null || true
}

extract_flag_value() {
  local flag_name="$1"
  shift
  local previous=""
  local arg

  for arg in "$@"; do
    if [[ "${previous}" == "${flag_name}" ]]; then
      printf '%s\n' "${arg}"
      return 0
    fi
    previous="${arg}"
  done

  return 1
}

extract_subcommand() {
  if [[ $# -eq 0 ]]; then
    return 1
  fi

  printf '%s\n' "${!#}"
}

launch_rootfs_sync() {
  local shim_namespace="$1"
  local shim_id="$2"

  [[ "${ROOTFS_SYNC_ENABLED}" == "1" ]] || return 0
  [[ -x "${ROOTFS_SYNC_HELPER}" ]] || {
    log "rootfs sync helper missing or not executable: ${ROOTFS_SYNC_HELPER}"
    return 0
  }

  FLOX_SHIM_SYNC_NAMESPACE="${shim_namespace}" \
  FLOX_SHIM_SYNC_ID="${shim_id}" \
  FLOX_SHIM_SYNC_LOG="${FLOX_SHIM_SYNC_LOG:-/var/log/rke2lab/flox-rootfs-sync.log}" \
  "${ROOTFS_SYNC_HELPER}" &

  log "launched rootfs sync helper pid=$! namespace=${shim_namespace} id=${shim_id}"
}

main() {
  local shim_namespace=""
  local shim_id=""
  local subcommand=""

  [[ -x "${REAL_SHIM}" ]] || {
    echo "missing real shim: ${REAL_SHIM}" >&2
    exit 1
  }

  shim_namespace="$(extract_flag_value -namespace "$@" || true)"
  shim_id="$(extract_flag_value -id "$@" || true)"
  subcommand="$(extract_subcommand "$@" || true)"

  log "argv=$*"
  log "resolved namespace=${shim_namespace:-<unset>} id=${shim_id:-<unset>} subcommand=${subcommand:-<unset>}"

  if [[ "${subcommand}" == "start" && -n "${shim_namespace}" && -n "${shim_id}" ]]; then
    launch_rootfs_sync "${shim_namespace}" "${shim_id}"
  fi

  exec "${REAL_SHIM}" "$@"
}

main "$@"
