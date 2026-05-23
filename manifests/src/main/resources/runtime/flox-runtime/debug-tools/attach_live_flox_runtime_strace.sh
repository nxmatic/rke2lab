#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/.sh.d/rke2lab-debug-tooling.sh"
rke2lab::debug:logging:setup "${BASH_SOURCE[0]}"

usage() {
	cat <<'EOF'
Usage:
  attach_live_flox_runtime_strace.sh --shim-id <sandbox-id> [--log-root <dir>] [--output-dir <dir>]
  attach_live_flox_runtime_strace.sh --pid <shim-pid> [--log-root <dir>] [--output-dir <dir>]

Description:
  Finds the long-lived flox-runtime-v2 daemon for a sandbox and attaches strace to all
  currently running threads. This captures the post-start Create RPC path, which
  is where create-time spec mutation and any nix/flox subprocesses should appear.

Notes:
  - Run as root on the node hosting the shim.
  - Leave the command running, then trigger the failing pod creation.
  - Stop with Ctrl-C after the failure reproduces.
EOF
}

LOG_ROOT_DEFAULT="/srv/host/rke2lab-share.d/flox-runtime-debug"
LOG_ROOT="${LOG_ROOT_DEFAULT}"
OUTPUT_DIR=""
TARGET_PID=""
TARGET_SHIM_ID=""

while [[ $# -gt 0 ]]; do
	case "$1" in
	--pid)
		TARGET_PID="${2:-}"
		shift 2
		;;
	--shim-id | --id)
		TARGET_SHIM_ID="${2:-}"
		shift 2
		;;
	--log-root)
		LOG_ROOT="${2:-}"
		shift 2
		;;
	--output-dir)
		OUTPUT_DIR="${2:-}"
		shift 2
		;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		echo "unknown argument: $1" >&2
		usage >&2
		exit 2
		;;
	esac
done

if [[ -z "${TARGET_PID}" && -z "${TARGET_SHIM_ID}" ]]; then
	echo "either --pid or --shim-id is required" >&2
	usage >&2
	exit 2
fi

find_strace() {
	local candidate
	for candidate in "$(command -v strace 2>/dev/null || true)" /usr/bin/strace /bin/strace; do
		if [[ -n "${candidate}" && -x "${candidate}" ]]; then
			printf '%s\n' "${candidate}"
			return 0
		fi
	done
	return 1
}

STRACE_BIN="$(find_strace || true)"
if [[ -z "${STRACE_BIN}" ]]; then
	echo "strace not found" >&2
	exit 1
fi

if [[ -z "${TARGET_PID}" ]]; then
	TARGET_PID="$(
		python3 - "${TARGET_SHIM_ID}" <<'PY'
import os
import sys

target = sys.argv[1]
matches = []
for entry in os.listdir('/proc'):
    if not entry.isdigit():
        continue
    pid = int(entry)
    cmdline_path = f'/proc/{pid}/cmdline'
    try:
        raw = open(cmdline_path, 'rb').read()
    except OSError:
        continue
    if not raw:
        continue
    argv = [part.decode('utf-8', 'replace') for part in raw.split(b'\0') if part]
    if not argv:
        continue
    joined = ' '.join(argv)
    if 'flox-runtime-v2' not in joined:
        continue
    if '-id' not in argv:
        continue
    try:
        shim_id = argv[argv.index('-id') + 1]
    except (ValueError, IndexError):
        continue
    if shim_id != target:
        continue
    if 'start' in argv:
        continue
    matches.append((pid, argv))

if not matches:
    sys.exit(1)

matches.sort()
print(matches[-1][0])
PY
	)" || {
		echo "could not find a live flox-runtime-v2 daemon for sandbox id ${TARGET_SHIM_ID}" >&2
		exit 1
	}
fi

if [[ ! -d "/proc/${TARGET_PID}" ]]; then
	echo "pid ${TARGET_PID} is not running" >&2
	exit 1
fi

read_proc_value() {
	local path="$1"
	python3 - "$path" <<'PY'
import os
import sys
path = sys.argv[1]
try:
    data = open(path, 'rb').read()
except OSError:
    sys.exit(1)
if path.endswith('/cmdline') or path.endswith('/environ'):
    parts = [part.decode('utf-8', 'replace') for part in data.split(b'\0') if part]
    for item in parts:
        print(item)
else:
    sys.stdout.write(data.decode('utf-8', 'replace'))
PY
}

CMDLINE_FILE="/proc/${TARGET_PID}/cmdline"
ENVIRON_FILE="/proc/${TARGET_PID}/environ"
STATUS_FILE="/proc/${TARGET_PID}/status"
TASK_DIR="/proc/${TARGET_PID}/task"

mapfile -t CMDLINE < <(read_proc_value "${CMDLINE_FILE}")
if [[ ${#CMDLINE[@]} -eq 0 ]]; then
	echo "failed to read cmdline for pid ${TARGET_PID}" >&2
	exit 1
fi

if [[ -z "${TARGET_SHIM_ID}" ]]; then
	prev=""
	for arg in "${CMDLINE[@]}"; do
		if [[ "${prev}" == "-id" ]]; then
			TARGET_SHIM_ID="${arg}"
			break
		fi
		prev="${arg}"
	done
fi

CWD="$(
	python3 - "${TARGET_PID}" <<'PY'
import os
import sys
pid = sys.argv[1]
try:
    print(os.readlink(f'/proc/{pid}/cwd'))
except OSError:
    sys.exit(1)
PY
	2>/dev/null || true
)"

PATH_VALUE="$(
	python3 - "${ENVIRON_FILE}" <<'PY'
import sys
path = sys.argv[1]
try:
    data = open(path, 'rb').read().split(b'\0')
except OSError:
    sys.exit(1)
for item in data:
    if item.startswith(b'PATH='):
        print(item.decode('utf-8', 'replace')[5:])
        break
PY
)"

WRAPPER_RUN_DIR="$(
	python3 - "${LOG_ROOT}" "${PATH_VALUE}" <<'PY'
import os
import sys
log_root = os.path.realpath(sys.argv[1])
path_value = sys.argv[2]
for entry in path_value.split(':'):
    if not entry:
        continue
    real = os.path.realpath(entry)
    if real.startswith(log_root + os.sep) and real.endswith('/bin'):
        print(os.path.dirname(real))
        break
PY
)"

if [[ -z "${OUTPUT_DIR}" ]]; then
	STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
	if [[ -n "${WRAPPER_RUN_DIR}" ]]; then
		OUTPUT_DIR="${WRAPPER_RUN_DIR}/attach-${STAMP}-${TARGET_PID}"
	else
		OUTPUT_DIR="${LOG_ROOT}/attach-${STAMP}-${TARGET_PID}"
	fi
fi

mkdir -p "${OUTPUT_DIR}"
META_FILE="${OUTPUT_DIR}/metadata.txt"
PREFIX="${OUTPUT_DIR}/strace"
COMMAND_TRACE_HINT=""
if [[ -n "${WRAPPER_RUN_DIR}" && -f "${WRAPPER_RUN_DIR}/command-trace.log" ]]; then
	COMMAND_TRACE_HINT="${WRAPPER_RUN_DIR}/command-trace.log"
fi

mapfile -t THREAD_IDS < <(find "${TASK_DIR}" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | LC_ALL=C sort -n)
if [[ ${#THREAD_IDS[@]} -eq 0 ]]; then
	echo "no threads found for pid ${TARGET_PID}" >&2
	exit 1
fi

{
	echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
	echo "shim_pid=${TARGET_PID}"
	echo "shim_id=${TARGET_SHIM_ID:-<unknown>}"
	echo "cwd=${CWD:-<unknown>}"
	echo "log_root=${LOG_ROOT}"
	echo "wrapper_run_dir=${WRAPPER_RUN_DIR:-<none>}"
	echo "command_trace_hint=${COMMAND_TRACE_HINT:-<none>}"
	echo "output_dir=${OUTPUT_DIR}"
	echo "strace_prefix=${PREFIX}"
	echo "strace_bin=${STRACE_BIN}"
	echo "cmdline=$(printf '%q ' "${CMDLINE[@]}")"
	echo "threads=${THREAD_IDS[*]}"
	echo "--- status ---"
	cat "${STATUS_FILE}" 2>/dev/null || true
	echo "--- environ (selected) ---"
	read_proc_value "${ENVIRON_FILE}" | grep -E '^(PATH|FLOX|NIX|RUST_LOG|RUST_BACKTRACE|_FLOX)' || true
} >"${META_FILE}"

printf 'Attaching to live flox-runtime-v2 daemon\n'
printf '  pid: %s\n' "${TARGET_PID}"
printf '  shim id: %s\n' "${TARGET_SHIM_ID:-<unknown>}"
printf '  output dir: %s\n' "${OUTPUT_DIR}"
printf '  strace prefix: %s\n' "${PREFIX}"
if [[ -n "${COMMAND_TRACE_HINT}" ]]; then
	printf '  wrapper command trace: %s\n' "${COMMAND_TRACE_HINT}"
fi
printf '  threads: %s\n' "${THREAD_IDS[*]}"
printf '\nNow reproduce the failing pod create, then stop this command with Ctrl-C.\n\n'

ATTACH_ARGS=()
for tid in "${THREAD_IDS[@]}"; do
	ATTACH_ARGS+=(-p "${tid}")
done

exec "${STRACE_BIN}" -f -ff -s 65535 -yy -o "${PREFIX}" "${ATTACH_ARGS[@]}"
