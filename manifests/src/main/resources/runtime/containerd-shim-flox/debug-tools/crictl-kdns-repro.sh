#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/.sh.d/rke2lab-debug-tooling.sh"
rke2lab::debug:flox:activate_if_present

CRI_CONFIG_FILE="${CRI_CONFIG_FILE:-/var/lib/rancher/rke2/agent/etc/crictl.yaml}"
CONTAINERD_LOG_FILE="${CONTAINERD_LOG_FILE:-/var/lib/rancher/rke2/agent/containerd/containerd.log}"
WORKDIR="$(mktemp -d /tmp/crictl-kdns-repro.XXXXXX)"
ARTIFACT_DIR="${ARTIFACT_DIR:-${SCRIPT_DIR}/crictl-kdns-repro-artifacts}"
FAILURE_SETTLE_SECONDS="${FAILURE_SETTLE_SECONDS:-3}"
PRESERVE_ON_FAILURE="${PRESERVE_ON_FAILURE:-1}"
SANDBOX_ONLY="${SANDBOX_ONLY:-0}"
SANDBOX_OBSERVE_SECONDS="${SANDBOX_OBSERVE_SECONDS:-5}"
POD_ID=""
CID=""
CRICTL_BIN=""
SOURCE_SANDBOX_ID=""
SOURCE_CONTAINER_ID=""
START_EC=0
LOG_FILE="${LOG_FILE:-${SCRIPT_DIR}/crictl-kdns-repro.log}"
LOG_MODE="${LOG_MODE:-truncate}"

rke2lab::debug:logging:setup "${BASH_SOURCE[0]}"

find_crictl() {
	local candidate
	for candidate in \
		"${CRICTL_BIN:-}" \
		"/var/lib/rancher/rke2/bin/crictl" \
		"/var/lib/rancher/rke2/agent/bin/crictl"; do
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

	echo "unable to locate crictl on host" >&2
	return 1
}

crictl_cmd() {
	"${CRICTL_BIN}" --config "${CRI_CONFIG_FILE}" "$@"
}

capture_live_kdns_objects() {
	crictl_cmd ps -a -o json >"${WORKDIR}/crictl-ps-a.json" || true
	crictl_cmd ps -a >"${WORKDIR}/crictl-ps-a.txt" || true

	read -r SOURCE_CONTAINER_ID SOURCE_SANDBOX_ID < <(
		python3 - "${WORKDIR}/crictl-ps-a.json" <<'PY'
import json
import pathlib
import sys
import uuid

path = pathlib.Path(sys.argv[1])
if not path.exists() or not path.read_text().strip():
    print()
    raise SystemExit(0)

data = json.loads(path.read_text())
for container in data.get("containers", []):
    metadata = container.get("metadata") or {}
    labels = container.get("labels") or {}
    if metadata.get("name") != "kdns":
        continue
    if labels.get("io.kubernetes.pod.namespace") != "kube-system":
        continue
    print(container.get("id", ""), container.get("podSandboxId", ""))
    raise SystemExit(0)

print()
PY
	)

	if [[ -n "${SOURCE_SANDBOX_ID}" ]]; then
		crictl_cmd inspectp "${SOURCE_SANDBOX_ID}" >"${WORKDIR}/source-sandbox.json"
	fi
	if [[ -n "${SOURCE_CONTAINER_ID}" ]]; then
		crictl_cmd inspect "${SOURCE_CONTAINER_ID}" >"${WORKDIR}/source-container.json"
	fi
}

generate_repro_configs() {
	python3 - "${WORKDIR}" <<'PY'
import json
import pathlib
import sys
import uuid

workdir = pathlib.Path(sys.argv[1])

def load_json(name):
    path = workdir / name
    if not path.exists():
        return None
    return json.loads(path.read_text())

def first_path(obj, *paths):
    for path in paths:
        current = obj
        found = True
        for key in path:
            if not isinstance(current, dict) or key not in current:
                found = False
                break
            current = current[key]
        if found and current is not None:
            return current
    return None

def deep_find_key(obj, names):
    if isinstance(obj, dict):
        for key, value in obj.items():
            if key in names and value is not None:
                return value
            nested = deep_find_key(value, names)
            if nested is not None:
                return nested
    elif isinstance(obj, list):
        for item in obj:
            nested = deep_find_key(item, names)
            if nested is not None:
                return nested
    return None

def derive_cgroup_parent(*values):
    for value in values:
        if not isinstance(value, str) or not value:
            continue
        if ":" in value:
            return value.split(":", 1)[0]
    return None

sandbox = load_json("source-sandbox.json") or {}
container = load_json("source-container.json") or {}

sandbox_cfg = first_path(sandbox, ("info", "config"), ("status", "config")) or {}
container_cfg = first_path(container, ("info", "config"), ("status", "config")) or {}

metadata = first_path(sandbox_cfg, ("metadata",)) or {}
annotations = first_path(sandbox_cfg, ("annotations",)) or {}
labels = first_path(sandbox_cfg, ("labels",)) or {}
linux_cfg = first_path(sandbox_cfg, ("linux",)) or {}

source_name = metadata.get("name") or "kdns"
source_namespace = metadata.get("namespace") or "kube-system"
repro_suffix = uuid.uuid4().hex[:8]
repro_name = f"{source_name}-manual-{repro_suffix}"[:63]
repro_uid = str(uuid.uuid4())

linux_cfg = dict(linux_cfg) if isinstance(linux_cfg, dict) else {}
cgroup_parent = deep_find_key(sandbox, {"cgroupParent", "cgroup_parent"})
if not cgroup_parent:
    cgroup_parent = derive_cgroup_parent(
        deep_find_key(sandbox, {"cgroupsPath", "cgroups_path"}),
        deep_find_key(container, {"cgroupsPath", "cgroups_path"}),
    )
if cgroup_parent:
    linux_cfg.setdefault("cgroup_parent", cgroup_parent)
else:
    # Provide default cgroup_parent like kubelet does when no source pod exists
    # Format: /kubepods.slice/kubepods-burstable.slice/kubepods-burstable-pod<UID>.slice
    # This ensures proper 3-component systemd cgroup path generation by containerd
    pod_uid_normalized = repro_uid.replace("-", "_")
    linux_cfg["cgroup_parent"] = f"/kubepods.slice/kubepods-burstable.slice/kubepods-burstable-pod{pod_uid_normalized}.slice"

pod_config = {
    "metadata": {
        "name": repro_name,
        "namespace": source_namespace,
        "attempt": 1,
        "uid": repro_uid,
    },
    "annotations": dict(annotations) if isinstance(annotations, dict) else {},
    "labels": dict(labels) if isinstance(labels, dict) else {},
    "linux": linux_cfg,
}
pod_config["annotations"]["flox.dev/environment"] = "nxmatic/kdns"
pod_config["labels"]["io.kubernetes.pod.name"] = repro_name
pod_config["labels"]["io.kubernetes.pod.namespace"] = source_namespace
pod_config["labels"]["io.kubernetes.pod.uid"] = repro_uid

image = first_path(container_cfg, ("image",))
if isinstance(image, dict):
    image_name = image.get("image")
else:
    image_name = image

container_config = {
    "metadata": {
        "name": first_path(container_cfg, ("metadata", "name")) or "kdns",
    },
    "image": {
        "image": image_name or "flox/empty:1.0.0",
    },
    "command": first_path(container_cfg, ("command",)) or ["kdns"],
    "args": first_path(container_cfg, ("args",)) or [],
    "envs": first_path(container_cfg, ("envs",)) or [],
    "mounts": first_path(container_cfg, ("mounts",)) or [],
    "annotations": dict(first_path(container_cfg, ("annotations",)) or {}),
    "labels": dict(first_path(container_cfg, ("labels",)) or {}),
    "linux": first_path(container_cfg, ("linux",)) or {},
    "log_path": f"kdns-manual/{repro_suffix}.log",
    "stdin": bool(first_path(container_cfg, ("stdin",)) or False),
    "stdin_once": bool(first_path(container_cfg, ("stdin_once",)) or False),
    "tty": bool(first_path(container_cfg, ("tty",)) or False),
    "working_dir": first_path(container_cfg, ("working_dir",)) or "",
}
container_config["annotations"]["flox.dev/environment"] = "networking/kdns"
container_config["labels"]["io.kubernetes.pod.name"] = repro_name
container_config["labels"]["io.kubernetes.pod.namespace"] = source_namespace
container_config["labels"]["io.kubernetes.pod.uid"] = repro_uid

# With NRI plugin approach, container must explicitly activate Flox environment
# NRI plugin mounts /nix/store read-only and provides FLOX_ENV_DIR, FLOX_BIN env vars
# Container command must run: $FLOX_BIN activate --dir $FLOX_ENV_DIR -- <actual-cmd>
if not container_config["command"]:
    container_config["command"] = [
        "/bin/sh",
        "-c",
        "${FLOX_BIN} activate --dir ${FLOX_ENV_DIR} -- kdns"
    ]

(workdir / "pod.json").write_text(json.dumps(pod_config, indent=2) + "\n")
(workdir / "container.json").write_text(json.dumps(container_config, indent=2) + "\n")
PY
}

cleanup() {
	set +e
	mkdir -p "${ARTIFACT_DIR}"
	cp -f "${WORKDIR}"/*.json "${ARTIFACT_DIR}/" >/dev/null 2>&1 || true
	cp -f "${WORKDIR}"/*.txt "${ARTIFACT_DIR}/" >/dev/null 2>&1 || true
	if [[ "${START_EC}" -ne 0 && "${PRESERVE_ON_FAILURE}" == "1" ]]; then
		echo "preserving failed repro sandbox: POD_ID=${POD_ID:-<none>} CID=${CID:-<none>}" >&2
		rm -rf "${WORKDIR}"
		return 0
	fi
	if [[ -n "${CID}" ]]; then
		crictl_cmd rm -f "${CID}" >/dev/null 2>&1 || true
	fi
	if [[ -n "${POD_ID}" ]]; then
		crictl_cmd rmp -f "${POD_ID}" >/dev/null 2>&1 || true
	fi
	rm -rf "${WORKDIR}"
}
trap cleanup EXIT

CRICTL_BIN="$(find_crictl)"

echo "== crictl config =="
echo "CRI_CONFIG_FILE=${CRI_CONFIG_FILE}"
echo "CONTAINERD_LOG_FILE=${CONTAINERD_LOG_FILE}"
echo "CRICTL_BIN=${CRICTL_BIN}"
echo "ARTIFACT_DIR=${ARTIFACT_DIR}"
echo "FAILURE_SETTLE_SECONDS=${FAILURE_SETTLE_SECONDS}"
echo "PRESERVE_ON_FAILURE=${PRESERVE_ON_FAILURE}"
echo "SANDBOX_ONLY=${SANDBOX_ONLY}"
echo "SANDBOX_OBSERVE_SECONDS=${SANDBOX_OBSERVE_SECONDS}"
echo

echo "== existing kdns objects =="
crictl_cmd pods | grep -i kdns || true
crictl_cmd ps -a | grep -i kdns || true
echo

capture_live_kdns_objects

echo "== source objects =="
echo "SOURCE_SANDBOX_ID=${SOURCE_SANDBOX_ID:-<none>}"
echo "SOURCE_CONTAINER_ID=${SOURCE_CONTAINER_ID:-<none>}"
if [[ -f "${WORKDIR}/source-sandbox.json" ]]; then
	echo "captured ${WORKDIR}/source-sandbox.json"
fi
if [[ -f "${WORKDIR}/source-container.json" ]]; then
	echo "captured ${WORKDIR}/source-container.json"
fi
echo

generate_repro_configs

echo "== pod config =="
cat "${WORKDIR}/pod.json"
echo
echo "== container config =="
cat "${WORKDIR}/container.json"
echo

echo "== creating sandbox with flox runtime handler =="
POD_ID="$(crictl_cmd runp --runtime flox "${WORKDIR}/pod.json")"
echo "POD_ID=${POD_ID}"

if [[ "${SANDBOX_ONLY}" == "1" ]]; then
	echo
	echo "== sandbox-only mode: immediate inspect =="
	crictl_cmd inspectp "${POD_ID}" | tee "${WORKDIR}/sandbox-only-inspect-initial.json" || true
	echo
	if [[ "${SANDBOX_OBSERVE_SECONDS}" -gt 0 ]]; then
		echo "== observing sandbox for ${SANDBOX_OBSERVE_SECONDS}s =="
		sleep "${SANDBOX_OBSERVE_SECONDS}"
		echo
	fi
	echo "== sandbox-only mode: final inspect =="
	crictl_cmd inspectp "${POD_ID}" | tee "${WORKDIR}/sandbox-only-inspect-final.json" || true
	echo
	echo "== recent containerd log tail =="
	if [[ -f "${CONTAINERD_LOG_FILE}" ]]; then
		tail -n 120 "${CONTAINERD_LOG_FILE}" | tee "${WORKDIR}/containerd-log-tail.txt" || true
	else
		echo "missing containerd log file: ${CONTAINERD_LOG_FILE}" >&2
	fi
	echo
	exit 0
fi

echo "== creating container =="
CID="$(crictl_cmd create "${POD_ID}" "${WORKDIR}/container.json" "${WORKDIR}/pod.json")"
echo "CID=${CID}"

echo "== starting container =="
set +e
START_OUTPUT="$(crictl_cmd start "${CID}" 2>&1)"
START_EC=$?
set -e
printf '%s\n' "${START_OUTPUT}"
printf '%s\n' "${START_OUTPUT}" >"${WORKDIR}/start-output.txt"
echo "START_EC=${START_EC}"
echo

if [[ "${START_EC}" -ne 0 && "${FAILURE_SETTLE_SECONDS}" -gt 0 ]]; then
	echo "== waiting for failure to settle =="
	sleep "${FAILURE_SETTLE_SECONDS}"
	echo
fi

echo "== inspect sandbox =="
crictl_cmd inspectp "${POD_ID}" || true
echo
echo "== inspect container =="
crictl_cmd inspect "${CID}" || true
echo

echo "== recent containerd log tail =="
if [[ -f "${CONTAINERD_LOG_FILE}" ]]; then
	tail -n 120 "${CONTAINERD_LOG_FILE}" | tee "${WORKDIR}/containerd-log-tail.txt" || true
else
	echo "missing containerd log file: ${CONTAINERD_LOG_FILE}" >&2
fi
echo

if [[ "${START_EC}" -ne 0 ]]; then
	exit "${START_EC}"
fi
