#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/.sh.d/rke2lab-debug-tooling.sh"
rke2lab::debug:flox:activate_if_present
rke2lab::debug:logging:setup "${BASH_SOURCE[0]}"

WATCH_ROOT="${WATCH_ROOT:-/run/k3s/containerd/io.containerd.runtime.v2.task/k8s.io}"
OUTPUT_ROOT="${OUTPUT_ROOT:-${SCRIPT_DIR}}"
SNAPSHOT_ROOT="${SNAPSHOT_ROOT:-${OUTPUT_ROOT%/}/kdns-bundle-watch.d}"
LOG_FILE="${LOG_FILE:-${OUTPUT_ROOT%/}/kdns-bundle-watch.log}"
POLL_INTERVAL_SECS="${POLL_INTERVAL_SECS:-0.2}"
MAX_RUNTIME_SECS="${MAX_RUNTIME_SECS:-0}"
CAPTURE_RETRIES="${CAPTURE_RETRIES:-20}"
CAPTURE_RETRY_SLEEP_SECS="${CAPTURE_RETRY_SLEEP_SECS:-0.25}"
KEEP_WATCHING_MATCHES="${KEEP_WATCHING_MATCHES:-1}"
MATCH_CONTAINER_NAMES="${MATCH_CONTAINER_NAMES:-kdns kdns-dlv}"

mkdir -p "${SNAPSHOT_ROOT}"

log() {
	printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

section() {
	printf '\n== %s ==\n' "$1"
}

bundle_matches() {
	local bundle_dir="$1"
	python3 - "$bundle_dir" "$MATCH_CONTAINER_NAMES" <<'PY'
import json
import pathlib
import sys

bundle_dir = pathlib.Path(sys.argv[1])
container_names = set(sys.argv[2].split())
config_path = bundle_dir / "config.json"
if not config_path.exists():
    print("WAIT")
    raise SystemExit(0)

try:
    config = json.loads(config_path.read_text())
except Exception:
    print("WAIT")
    raise SystemExit(0)

annotations = config.get("annotations") or {}
container_name = annotations.get("io.kubernetes.cri.container-name", "")
namespace = annotations.get("io.kubernetes.cri.sandbox-namespace", "")
pod_name = annotations.get("io.kubernetes.cri.sandbox-name", "")

if namespace != "kube-system":
    print("SKIP")
elif container_name in container_names or "kdns" in pod_name:
    print("MATCH")
else:
    print("SKIP")
PY
}

copy_if_exists() {
	local src="$1"
	local dst="$2"

	if [[ -e "${src}" || -L "${src}" ]]; then
		mkdir -p "$(dirname -- "${dst}")"
		cp -a "${src}" "${dst}"
	fi
}

snapshot_bundle() {
	local bundle_dir="$1"
	local bundle_id="$2"
	local snapshot_dir="$3"
	local rootfs_dir="${bundle_dir}/rootfs"

	mkdir -p "${snapshot_dir}"

	{
		echo "captured_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
		echo "bundle_id=${bundle_id}"
		echo "bundle_dir=${bundle_dir}"
		echo "bundle_exists=$([[ -d "${bundle_dir}" ]] && echo yes || echo no)"
		echo "rootfs_exists=$([[ -d "${rootfs_dir}" ]] && echo yes || echo no)"
		echo "bundle_flox_exists=$([[ -d "${bundle_dir}/.flox" ]] && echo yes || echo no)"
		echo "rootfs_flox_exists=$([[ -d "${rootfs_dir}/.flox" ]] && echo yes || echo no)"
	} >"${snapshot_dir}/meta.env"

	copy_if_exists "${bundle_dir}/config.json" "${snapshot_dir}/config.json"
	copy_if_exists "${bundle_dir}/log.json" "${snapshot_dir}/log.json"
	copy_if_exists "${bundle_dir}/init.pid" "${snapshot_dir}/init.pid"

	if [[ -d "${bundle_dir}" ]]; then
		find "${bundle_dir}" -maxdepth 3 \( -type d -o -type f -o -type l \) | sort >"${snapshot_dir}/bundle-find.txt" || true
		ls -la "${bundle_dir}" >"${snapshot_dir}/bundle-ls.txt" || true
	fi

	if [[ -d "${bundle_dir}/.flox" ]]; then
		find "${bundle_dir}/.flox" -maxdepth 6 \( -type d -o -type f -o -type l \) | sort >"${snapshot_dir}/bundle-flox-find.txt" || true
		ls -laR "${bundle_dir}/.flox" >"${snapshot_dir}/bundle-flox-ls.txt" || true
		tar -C "${bundle_dir}" -cf "${snapshot_dir}/bundle-flox.tar" .flox || true
	fi

	if [[ -d "${rootfs_dir}" ]]; then
		find "${rootfs_dir}" -maxdepth 2 \( -type d -o -type f -o -type l \) | sort >"${snapshot_dir}/rootfs-find.txt" || true
		copy_if_exists "${rootfs_dir}/etc/passwd" "${snapshot_dir}/rootfs-etc/passwd"
		copy_if_exists "${rootfs_dir}/etc/group" "${snapshot_dir}/rootfs-etc/group"
		copy_if_exists "${rootfs_dir}/bin/sh" "${snapshot_dir}/rootfs-bin/sh"
		copy_if_exists "${rootfs_dir}/usr/bin/sh" "${snapshot_dir}/rootfs-usr-bin/sh"
	fi

	if [[ -d "${rootfs_dir}/.flox" ]]; then
		find "${rootfs_dir}/.flox" -maxdepth 6 \( -type d -o -type f -o -type l \) | sort >"${snapshot_dir}/rootfs-flox-find.txt" || true
		ls -laR "${rootfs_dir}/.flox" >"${snapshot_dir}/rootfs-flox-ls.txt" || true
		tar -C "${rootfs_dir}" -cf "${snapshot_dir}/rootfs-flox.tar" .flox || true
	fi
}

watch_bundle_lifecycle() {
	local bundle_dir="$1"
	local bundle_id="$2"
	local watch_dir="$3"
	local attempt=1

	mkdir -p "${watch_dir}"
	log "watching matched bundle ${bundle_id}"

	while ((attempt <= CAPTURE_RETRIES)); do
		local phase_dir
		phase_dir="${watch_dir}/capture-$(printf '%03d' "${attempt}")"
		snapshot_bundle "${bundle_dir}" "${bundle_id}" "${phase_dir}"

		if [[ ! -d "${bundle_dir}" ]]; then
			log "bundle ${bundle_id} disappeared after capture ${attempt}"
			return 0
		fi

		if [[ -d "${bundle_dir}/rootfs/.flox" ]]; then
			log "bundle ${bundle_id} now has rootfs/.flox at capture ${attempt}"
			if [[ "${KEEP_WATCHING_MATCHES}" != "1" ]]; then
				return 0
			fi
		fi

		sleep "${CAPTURE_RETRY_SLEEP_SECS}"
		((attempt += 1))
	done

	log "bundle ${bundle_id} still present after ${CAPTURE_RETRIES} captures"
}

section "watch configuration"
cat <<EOF
WATCH_ROOT=${WATCH_ROOT}
SNAPSHOT_ROOT=${SNAPSHOT_ROOT}
LOG_FILE=${LOG_FILE}
POLL_INTERVAL_SECS=${POLL_INTERVAL_SECS}
MAX_RUNTIME_SECS=${MAX_RUNTIME_SECS}
CAPTURE_RETRIES=${CAPTURE_RETRIES}
CAPTURE_RETRY_SLEEP_SECS=${CAPTURE_RETRY_SLEEP_SECS}
KEEP_WATCHING_MATCHES=${KEEP_WATCHING_MATCHES}
MATCH_CONTAINER_NAMES=${MATCH_CONTAINER_NAMES}
EOF

if [[ ! -d "${WATCH_ROOT}" ]]; then
	log "watch root does not exist: ${WATCH_ROOT}"
	exit 1
fi

started_epoch="$(date +%s)"
declare -A seen=()

while :; do
	while IFS= read -r -d '' bundle_dir; do
		bundle_id="$(basename -- "${bundle_dir}")"
		[[ -n "${bundle_id}" ]] || continue

		if [[ -n "${seen[${bundle_id}]+x}" ]]; then
			continue
		fi

		decision="$(bundle_matches "${bundle_dir}")"
		case "${decision}" in
		MATCH)
			seen["${bundle_id}"]=matched
			bundle_watch_root="${SNAPSHOT_ROOT%/}/${bundle_id}"
			mkdir -p "${bundle_watch_root}"
			log "matched bundle ${bundle_id}; capturing lifecycle snapshots"
			watch_bundle_lifecycle "${bundle_dir}" "${bundle_id}" "${bundle_watch_root}"
			;;
		SKIP)
			seen["${bundle_id}"]=skipped
			;;
		WAIT) ;;
		*)
			log "unexpected decision ${decision} for ${bundle_id}; treating as wait"
			;;
		esac
	done < <(find "${WATCH_ROOT}" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)

	if [[ "${MAX_RUNTIME_SECS}" != "0" ]]; then
		now_epoch="$(date +%s)"
		if ((now_epoch - started_epoch >= MAX_RUNTIME_SECS)); then
			log "max runtime reached; exiting watcher"
			break
		fi
	fi

	sleep "${POLL_INTERVAL_SECS}"
done

section "done"
log "artifacts written under ${SNAPSHOT_ROOT}"
