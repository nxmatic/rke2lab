#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/.sh.d/rke2lab-debug-tooling.sh"
rke2lab::debug:flox:activate_if_present

CRI_CONFIG_FILE="${CRI_CONFIG_FILE:-/var/lib/rancher/rke2/agent/etc/crictl.yaml}"
CONTAINERD_LOG_FILE="${CONTAINERD_LOG_FILE:-/var/lib/rancher/rke2/agent/containerd/containerd.log}"
SHIM_DEBUG_ROOT="${SHIM_DEBUG_ROOT:-/srv/host/rke2lab-share.d/containerd-shim-debug}"
REPRO_SCRIPT="${REPRO_SCRIPT:-/srv/host/rke2lab-share.d/crictl-kdns-repro.sh}"
RUN_REPRO="${RUN_REPRO:-0}"
REPRO_SANDBOX_ONLY="${REPRO_SANDBOX_ONLY:-0}"
CAPTURE_MODE="${CAPTURE_MODE:-watch-only}"
BUNDLE_WATCH_ENABLED="${BUNDLE_WATCH_ENABLED:-0}"
BUNDLE_WATCH_SCRIPT="${BUNDLE_WATCH_SCRIPT:-${SCRIPT_DIR}/kdns-containerd-bundle-watch.sh}"
BUNDLE_WATCH_MAX_RUNTIME_SECS="${BUNDLE_WATCH_MAX_RUNTIME_SECS:-45}"
BUNDLE_WATCH_POLL_INTERVAL_SECS="${BUNDLE_WATCH_POLL_INTERVAL_SECS:-0.2}"
BUNDLE_WATCH_CAPTURE_RETRIES="${BUNDLE_WATCH_CAPTURE_RETRIES:-20}"
BUNDLE_WATCH_CAPTURE_RETRY_SLEEP_SECS="${BUNDLE_WATCH_CAPTURE_RETRY_SLEEP_SECS:-0.25}"
TAIL_LINES="${TAIL_LINES:-250}"
LATEST_DEBUG_RUNS="${LATEST_DEBUG_RUNS:-4}"
OUTPUT_ROOT="${OUTPUT_ROOT:-${SCRIPT_DIR}}"
LOG_FILE="${LOG_FILE:-${OUTPUT_ROOT%/}/kdns-containerd-remote-capture.log}"
LOG_MODE="${LOG_MODE:-truncate}"

rke2lab::debug:logging:setup "${BASH_SOURCE[0]}"

mkdir -p "${OUTPUT_ROOT}/kdns-containerd-remote-capture.d"
WORKDIR="$(mktemp -d "${OUTPUT_ROOT%/}/kdns-containerd-remote-capture.d/XXXXXX")"
BUNDLE_WATCH_SNAPSHOT_ROOT="${BUNDLE_WATCH_SNAPSHOT_ROOT:-${WORKDIR}/bundle-watch}"
BUNDLE_WATCH_LOG_FILE="${BUNDLE_WATCH_LOG_FILE:-${WORKDIR}/bundle-watch.log}"
BUNDLE_WATCH_PID=""

cleanup() {
	if [[ -n "${BUNDLE_WATCH_PID:-}" ]] && kill -0 "${BUNDLE_WATCH_PID}" 2>/dev/null; then
		kill "${BUNDLE_WATCH_PID}" 2>/dev/null || true
		wait "${BUNDLE_WATCH_PID}" 2>/dev/null || true
	fi

	if [[ "${PRESERVE_WORKDIR:-1}" != "1" ]]; then
		rm -rf "${WORKDIR}"
	fi
}
trap cleanup EXIT

find_crictl() {
	local candidate
	for candidate in \
		"${CRICTL_BIN:-}" \
		/var/lib/rancher/rke2/bin/crictl \
		/var/lib/rancher/rke2/agent/bin/crictl; do
		if [[ -n "${candidate}" && -x "${candidate}" ]]; then
			printf '%s\n' "${candidate}"
			return 0
		fi
	done

	while IFS= read -r candidate; do
		if [[ -x "${candidate}" ]]; then
			printf '%s\n' "${candidate}"
			return 0
		fi
	done < <(find /var/lib/rancher/rke2 -path '*/bin/crictl' 2>/dev/null | sort)

	echo "unable to locate crictl" >&2
	return 1
}

CRICTL_BIN="$(find_crictl)"

crictl_cmd() {
	"${CRICTL_BIN}" --config "${CRI_CONFIG_FILE}" "$@"
}

section() {
	printf '\n== %s ==\n' "$1"
}

start_bundle_watcher() {
	if [[ "${BUNDLE_WATCH_ENABLED}" != "1" ]]; then
		return 0
	fi

	if [[ ! -f "${BUNDLE_WATCH_SCRIPT}" ]]; then
		echo "bundle watcher script not found: ${BUNDLE_WATCH_SCRIPT}" >&2
		return 1
	fi

	section "bundle watcher"
	cat <<EOF
BUNDLE_WATCH_SCRIPT=${BUNDLE_WATCH_SCRIPT}
BUNDLE_WATCH_MAX_RUNTIME_SECS=${BUNDLE_WATCH_MAX_RUNTIME_SECS}
BUNDLE_WATCH_POLL_INTERVAL_SECS=${BUNDLE_WATCH_POLL_INTERVAL_SECS}
BUNDLE_WATCH_CAPTURE_RETRIES=${BUNDLE_WATCH_CAPTURE_RETRIES}
BUNDLE_WATCH_CAPTURE_RETRY_SLEEP_SECS=${BUNDLE_WATCH_CAPTURE_RETRY_SLEEP_SECS}
BUNDLE_WATCH_SNAPSHOT_ROOT=${BUNDLE_WATCH_SNAPSHOT_ROOT}
BUNDLE_WATCH_LOG_FILE=${BUNDLE_WATCH_LOG_FILE}
EOF

	mkdir -p "${BUNDLE_WATCH_SNAPSHOT_ROOT}"

	WATCH_ROOT="/run/k3s/containerd/io.containerd.runtime.v2.task/k8s.io" \
		SNAPSHOT_ROOT="${BUNDLE_WATCH_SNAPSHOT_ROOT}" \
		LOG_FILE="${BUNDLE_WATCH_LOG_FILE}" \
		MAX_RUNTIME_SECS="${BUNDLE_WATCH_MAX_RUNTIME_SECS}" \
		POLL_INTERVAL_SECS="${BUNDLE_WATCH_POLL_INTERVAL_SECS}" \
		CAPTURE_RETRIES="${BUNDLE_WATCH_CAPTURE_RETRIES}" \
		CAPTURE_RETRY_SLEEP_SECS="${BUNDLE_WATCH_CAPTURE_RETRY_SLEEP_SECS}" \
		bash "${BUNDLE_WATCH_SCRIPT}" &

	BUNDLE_WATCH_PID=$!
	echo "BUNDLE_WATCH_PID=${BUNDLE_WATCH_PID}"
}

wait_for_bundle_watcher() {
	if [[ -z "${BUNDLE_WATCH_PID:-}" ]]; then
		return 0
	fi

	section "waiting for bundle watcher"
	wait "${BUNDLE_WATCH_PID}"
	BUNDLE_WATCH_PID=""

	if [[ -f "${BUNDLE_WATCH_LOG_FILE}" ]]; then
		echo "bundle watcher log: ${BUNDLE_WATCH_LOG_FILE}"
	fi
}

section "capture run"
cat <<EOF
started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
SCRIPT_PATH=${BASH_SOURCE[0]}
CAPTURE_MODE=${CAPTURE_MODE}
LOG_FILE=${LOG_FILE}
LOG_MODE=${LOG_MODE}
BUNDLE_WATCH_ENABLED=${BUNDLE_WATCH_ENABLED}
EOF

capture_current_state() {
	section "host paths"
	cat <<EOF | tee "${WORKDIR}/host-paths.txt"
CRI_CONFIG_FILE=${CRI_CONFIG_FILE}
CONTAINERD_LOG_FILE=${CONTAINERD_LOG_FILE}
SHIM_DEBUG_ROOT=${SHIM_DEBUG_ROOT}
REPRO_SCRIPT=${REPRO_SCRIPT}
RUN_REPRO=${RUN_REPRO}
REPRO_SANDBOX_ONLY=${REPRO_SANDBOX_ONLY}
CRICTL_BIN=${CRICTL_BIN}
WORKDIR=${WORKDIR}
LOG_FILE=${LOG_FILE}
LOG_MODE=${LOG_MODE}
EOF

	section "current kdns pods"
	crictl_cmd pods | tee "${WORKDIR}/crictl-pods.txt" || true

	section "current kdns containers"
	crictl_cmd ps -a | tee "${WORKDIR}/crictl-ps-a.txt" || true

	crictl_cmd ps -a -o json >"${WORKDIR}/crictl-ps-a.json" || true
	crictl_cmd pods -o json >"${WORKDIR}/crictl-pods.json" || true

	python3 - "${WORKDIR}/crictl-ps-a.json" "${WORKDIR}/crictl-pods.json" >"${WORKDIR}/kdns-ids.env" <<'PY'
import json
import pathlib
import sys

containers_path = pathlib.Path(sys.argv[1])
pods_path = pathlib.Path(sys.argv[2])

container_id = ""
pod_id = ""
container_ids = []

if containers_path.exists() and containers_path.read_text().strip():
    data = json.loads(containers_path.read_text())
    for container in data.get("containers", []):
        metadata = container.get("metadata") or {}
        labels = container.get("labels") or {}
        if labels.get("io.kubernetes.pod.namespace") != "kube-system":
            continue

        name = metadata.get("name")
        candidate = container.get("id", "")

        if name == "kdns" and not container_id:
            container_id = candidate
            pod_id = container.get("podSandboxId", "")

        if name in {"kdns", "kdns-dlv"} and candidate and candidate not in container_ids:
            container_ids.append(candidate)

if not pod_id and pods_path.exists() and pods_path.read_text().strip():
    data = json.loads(pods_path.read_text())
    for pod in data.get("items", []):
        metadata = pod.get("metadata") or {}
        if metadata.get("namespace") != "kube-system":
            continue
        if "kdns" not in (metadata.get("name") or ""):
            continue
        pod_id = pod.get("id", "")
        break

print(f"KDNS_CONTAINER_ID={container_id}")
print(f"KDNS_POD_ID={pod_id}")
print("KDNS_CONTAINER_IDS='{}'".format(" ".join(container_ids)))
PY

	# shellcheck disable=SC1090
	source "${WORKDIR}/kdns-ids.env"

	section "resolved kdns ids"
	cat "${WORKDIR}/kdns-ids.env"

	if [[ -n "${KDNS_POD_ID:-}" ]]; then
		section "inspect current sandbox"
		crictl_cmd inspectp "${KDNS_POD_ID}" | tee "${WORKDIR}/inspectp-current.json" || true
	fi

	if [[ -n "${KDNS_CONTAINER_ID:-}" ]]; then
		section "inspect current container"
		crictl_cmd inspect "${KDNS_CONTAINER_ID}" | tee "${WORKDIR}/inspect-current.json" || true
	fi
}

capture_bundle_flox_views() {
	section "bundle /.flox snapshots"

	if [[ -z "${KDNS_CONTAINER_IDS:-}" ]]; then
		echo "no kdns-related container ids resolved from crictl ps -a" | tee "${WORKDIR}/bundle-flox-missing.txt"
		return 0
	fi

	local container_id
	local bundle_dir
	local rootfs_dir
	local flox_dir

	for container_id in ${KDNS_CONTAINER_IDS}; do
		bundle_dir="/run/k3s/containerd/io.containerd.runtime.v2.task/k8s.io/${container_id}"
		rootfs_dir="${bundle_dir}/rootfs"
		flox_dir="${bundle_dir}/.flox"

		{
			echo "CONTAINER_ID=${container_id}"
			echo "BUNDLE_DIR=${bundle_dir}"
			echo "ROOTFS_DIR=${rootfs_dir}"
			echo "BUNDLE_EXISTS=$([[ -d "${bundle_dir}" ]] && echo yes || echo no)"
			echo "FLOX_DIR_EXISTS=$([[ -d "${flox_dir}" ]] && echo yes || echo no)"
			echo "ROOTFS_FLOX_EXISTS=$([[ -d "${rootfs_dir}/.flox" ]] && echo yes || echo no)"
			echo

			if [[ -d "${bundle_dir}" ]]; then
				echo "--- bundle ls -la ---"
				ls -la "${bundle_dir}"
				echo
			fi

			if [[ -d "${flox_dir}" ]]; then
				echo "--- bundle .flox find (maxdepth 4) ---"
				find "${flox_dir}" -maxdepth 4 \( -type d -o -type f -o -type l \) | sort
				echo

				echo "--- bundle .flox ls -laR ---"
				ls -laR "${flox_dir}"
				echo
			fi

			if [[ -d "${rootfs_dir}/.flox" ]]; then
				echo "--- rootfs /.flox find (maxdepth 4) ---"
				find "${rootfs_dir}/.flox" -maxdepth 4 \( -type d -o -type f -o -type l \) | sort
				echo
			fi
		} | tee "${WORKDIR}/bundle-flox-${container_id}.txt"
	done
}

capture_containerd_logs() {
	section "recent containerd log tail"
	if [[ -f "${CONTAINERD_LOG_FILE}" ]]; then
		tail -n "${TAIL_LINES}" "${CONTAINERD_LOG_FILE}" | tee "${WORKDIR}/containerd-log-tail.txt"
	else
		echo "missing containerd log file: ${CONTAINERD_LOG_FILE}" | tee "${WORKDIR}/containerd-log-tail.txt"
	fi

	section "filtered containerd events"
	if [[ -f "${CONTAINERD_LOG_FILE}" ]]; then
		grep -iE \
			'kdns|failed to create shim task|ttrpc: closed|StartContainer|shim disconnected|failed to calculate store paths|create-time spec mutation failed' \
			"${CONTAINERD_LOG_FILE}" | tail -n "${TAIL_LINES}" | tee "${WORKDIR}/containerd-log-filtered.txt" || true
	fi
}

capture_shim_debug() {
	section "latest shim debug runs"
	if [[ ! -d "${SHIM_DEBUG_ROOT}" ]]; then
		echo "missing shim debug root: ${SHIM_DEBUG_ROOT}" | tee "${WORKDIR}/shim-debug-missing.txt"
		return 0
	fi

	find "${SHIM_DEBUG_ROOT}" -mindepth 1 -maxdepth 1 -type d | sort -r | head -n "${LATEST_DEBUG_RUNS}" | tee "${WORKDIR}/latest-shim-runs.txt"

	while IFS= read -r run_dir; do
		[[ -n "${run_dir}" ]] || continue
		run_name="$(basename "${run_dir}")"
		section "shim run ${run_name}"
		{
			echo "RUN_DIR=${run_dir}"
			for f in wrapper.log shim.stdout.log shim.stderr.log live-attach.log live-attach.meta command-trace.log argv.txt env.txt; do
				if [[ -f "${run_dir}/${f}" ]]; then
					echo
					echo "--- ${f} ---"
					tail -n 120 "${run_dir}/${f}" || true
				fi
			done
			echo
			echo "--- grepped live strace ---"
			grep -iE \
				'ttrpc|failed|error|panic|SIG[A-Z]+|exited with|No such file|permission denied|create-time spec mutation failed|nix path-info|flox.conf' \
				"${run_dir}"/live-strace.* 2>/dev/null | tail -n 200 || true
		} | tee "${WORKDIR}/shim-${run_name}.txt"
	done <"${WORKDIR}/latest-shim-runs.txt"
}

run_repro_if_requested() {
	if [[ "${RUN_REPRO}" != "1" ]]; then
		return 0
	fi

	section "optional repro run"
	if [[ ! -x "${REPRO_SCRIPT}" ]]; then
		echo "repro script not found or not executable: ${REPRO_SCRIPT}" | tee "${WORKDIR}/repro-missing.txt"
		return 0
	fi

	SANDBOX_ONLY="${REPRO_SANDBOX_ONLY}" \
		ARTIFACT_DIR="${WORKDIR}/repro-artifacts" \
		CONTAINERD_LOG_FILE="${CONTAINERD_LOG_FILE}" \
		CRI_CONFIG_FILE="${CRI_CONFIG_FILE}" \
		"${REPRO_SCRIPT}" | tee "${WORKDIR}/repro-output.txt"
}

case "${CAPTURE_MODE}" in
full)
	start_bundle_watcher
	capture_current_state
	capture_bundle_flox_views
	capture_containerd_logs
	capture_shim_debug
	run_repro_if_requested
	wait_for_bundle_watcher
	;;
watch-only)
	BUNDLE_WATCH_ENABLED=1
	start_bundle_watcher
	wait_for_bundle_watcher
	;;
*)
	echo "unsupported CAPTURE_MODE=${CAPTURE_MODE}; expected 'full' or 'watch-only'" >&2
	exit 1
	;;
esac

section "done"
echo "artifacts written to ${WORKDIR}"
