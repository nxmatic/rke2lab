#!/usr/bin/env bash
set -euxo pipefail

: "${CONTAINERD_SHIM_FLOX_V2_SYNC_NAMESPACE:?CONTAINERD_SHIM_FLOX_V2_SYNC_NAMESPACE is required}"
: "${CONTAINERD_SHIM_FLOX_V2_SYNC_ID:?CONTAINERD_SHIM_FLOX_V2_SYNC_ID is required}"

SYNC_LOG="${CONTAINERD_SHIM_FLOX_V2_SYNC_LOG:-/var/log/rke2lab/flox-rootfs-sync.log}"
BUNDLE_ROOT="${CONTAINERD_SHIM_FLOX_V2_BUNDLE_ROOT:-/run/k3s/containerd/io.containerd.runtime.v2.task}"
WAIT_TIMEOUT_SECS="${CONTAINERD_SHIM_FLOX_V2_SYNC_TIMEOUT_SECS:-15}"
POLL_INTERVAL_SECS="${CONTAINERD_SHIM_FLOX_V2_SYNC_POLL_INTERVAL_SECS:-0.1}"

ensure_log_target() {
	local log_dir

	log_dir="$(dirname -- "${SYNC_LOG}")"
	if mkdir -p "${log_dir}" 2>/dev/null; then
		: >>"${SYNC_LOG}" 2>/dev/null || true
		return 0
	fi

	SYNC_LOG="/tmp/flox-rootfs-sync.log"
	: >>"${SYNC_LOG}" 2>/dev/null || true
}

ensure_log_target

log() {
	local ts

	ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
	printf '[%s] %s\n' "${ts}" "$*" >>"${SYNC_LOG}" 2>/dev/null || true
}

is_dir_empty() {
	local dir_path="$1"

	[[ -d "${dir_path}" ]] || return 0
	[[ -z "$(find "${dir_path}" -mindepth 1 -print -quit 2>/dev/null || true)" ]]
}

sync_bundle_flox_into_rootfs() {
	local bundle_dir="$1"
	local rootfs_dir="$2"
	local bundle_flox="${bundle_dir}/.flox"
	local rootfs_flox="${rootfs_dir}/.flox"

	mkdir -p "${rootfs_flox}"

	if ! is_dir_empty "${rootfs_flox}"; then
		log "rootfs flox dir already populated; skipping sync bundle=${bundle_dir}"
		return 0
	fi

	cp -a "${bundle_flox}/." "${rootfs_flox}/"
	log "synced bundle .flox into rootfs bundle=${bundle_dir}"
}

main() {
	local bundle_dir rootfs_dir started_at now elapsed_secs

	bundle_dir="${BUNDLE_ROOT%/}/${CONTAINERD_SHIM_FLOX_V2_SYNC_NAMESPACE}/${CONTAINERD_SHIM_FLOX_V2_SYNC_ID}"
	rootfs_dir="${bundle_dir}/rootfs"
	started_at="$(date +%s)"

	log "watching bundle namespace=${CONTAINERD_SHIM_FLOX_V2_SYNC_NAMESPACE} id=${CONTAINERD_SHIM_FLOX_V2_SYNC_ID} bundle=${bundle_dir}"

	while :; do
		if [[ -d "${bundle_dir}/.flox" && -d "${rootfs_dir}" ]]; then
			sync_bundle_flox_into_rootfs "${bundle_dir}" "${rootfs_dir}"
			exit 0
		fi

		if [[ ! -d "${bundle_dir}" && ! -e "${bundle_dir}" ]]; then
			log "bundle vanished before sync bundle=${bundle_dir}"
			exit 0
		fi

		now="$(date +%s)"
		elapsed_secs=$((now - started_at))
		if ((elapsed_secs >= WAIT_TIMEOUT_SECS)); then
			log "timed out waiting for bundle/rootfs bundle=${bundle_dir}"
			exit 0
		fi

		sleep "${POLL_INTERVAL_SECS}"
	done
}

main "$@"
