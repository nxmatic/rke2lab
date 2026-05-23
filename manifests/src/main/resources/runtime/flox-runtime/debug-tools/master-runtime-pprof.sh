#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/.sh.d/rke2lab-debug-tooling.sh"

DEFAULT_CONTAINERD_ADDRESS="${RKE2LAB_CONTAINERD_ADDRESS:-/run/k3s/containerd/containerd.sock}"
DEFAULT_CONTAINERD_NAMESPACE="${RKE2LAB_CONTAINERD_NAMESPACE:-k8s.io}"
REMOTE_FLOX_DIR="${RKE2LAB_REMOTE_FLOX_DIR:-/var/lib/rancher/rke2}"
DEFAULT_PPROF_TIMEOUT="${RKE2LAB_PPROF_TIMEOUT:-15s}"
LOG_FILE="${LOG_FILE:-${SCRIPT_DIR}/master-shim-pprof.log}"
LOG_MODE="${LOG_MODE:-truncate}"

rke2lab::debug:logging:setup "${BASH_SOURCE[0]}"

usage() {
	cat <<EOF
Usage:
  $(basename "$0") [global-options] [command] [command-args...]

Commands:
  list
      List live Flox shim processes seen on the master.

  goroutines [shim-id-or-prefix] [pprof-args...]
  heap [shim-id-or-prefix] [pprof-args...]
  profile [shim-id-or-prefix] [pprof-args...]
  trace [shim-id-or-prefix] [pprof-args...]
  block [shim-id-or-prefix] [pprof-args...]
  threadcreate [shim-id-or-prefix] [pprof-args...]
      Run ctr shim pprof for the selected live Flox shim.
      If no shim ID is provided, the newest live Flox shim is selected.

Global options:
  --address PATH         Default containerd socket path (default: ${DEFAULT_CONTAINERD_ADDRESS})
  --namespace NAME       Default containerd namespace (default: ${DEFAULT_CONTAINERD_NAMESPACE})
	--flox-dir PATH        Remote Flox environment directory (default: ${REMOTE_FLOX_DIR})
	--timeout DURATION     Timeout for pprof requests; 0 disables it (default: ${DEFAULT_PPROF_TIMEOUT})
  -h, --help             Show this help text

Environment overrides:
  RKE2LAB_CONTAINERD_ADDRESS
  RKE2LAB_CONTAINERD_NAMESPACE
	RKE2LAB_REMOTE_FLOX_DIR
	RKE2LAB_PPROF_TIMEOUT

Examples:
  $(basename "$0") list
  $(basename "$0") goroutines
  $(basename "$0") goroutines 81ac3dbf
  $(basename "$0") heap 40299188 --debug 2
EOF
}

die() {
	printf 'error: %s\n' "$*" >&2
	exit 1
}

activate_remote_env() {
	rke2lab::debug:flox:activate_if_present "${REMOTE_FLOX_DIR}"
}

run_cmd() {
	"$@"
}

run_with_timeout() {
	local duration="$1"
	shift

	if [[ "${duration}" == "0" ]]; then
		run_cmd "$@"
		return 0
	fi

	if command -v timeout >/dev/null 2>&1; then
		timeout --foreground --signal=INT --kill-after=5s "${duration}" "$@"
		return $?
	fi

	printf 'warning: timeout command not available; running without timeout\n' >&2
	run_cmd "$@"
}

hash_logical_path() {
	local logical_path="$1"
	local digest=""

	if command -v sha256sum >/dev/null 2>&1; then
		digest="$(printf '%s' "${logical_path}" | sha256sum | awk '{print $1}')"
	elif command -v shasum >/dev/null 2>&1; then
		digest="$(printf '%s' "${logical_path}" | shasum -a 256 | awk '{print $1}')"
	else
		die "could not find sha256sum or shasum in the activated Flox environment"
	fi

	printf '/run/containerd/s/%s\n' "${digest}"
}

REMOTE_CTR_BIN=""

resolve_remote_ctr() {
	if [[ -n "${REMOTE_CTR_BIN}" ]]; then
		printf '%s\n' "${REMOTE_CTR_BIN}"
		return 0
	fi

	local candidate
	local -a candidates=(
		"ctr"
		"/var/lib/rancher/rke2/bin/ctr"
		"/run/current-system/sw/bin/ctr"
		"/usr/local/bin/ctr"
		"/usr/bin/ctr"
	)

	for candidate in "${candidates[@]}"; do
		if [[ "${candidate}" == "ctr" ]]; then
			candidate="$(command -v ctr 2>/dev/null || true)"
			if [[ -n "${candidate}" ]]; then
				REMOTE_CTR_BIN="${candidate}"
				printf '%s\n' "${REMOTE_CTR_BIN}"
				return 0
			fi
		elif [[ -x "${candidate}" ]]; then
			REMOTE_CTR_BIN="${candidate}"
			printf '%s\n' "${REMOTE_CTR_BIN}"
			return 0
		fi
	done

	die "could not find ctr in the activated Flox environment"
}

collect_live_containerd_shim_flox_v2s() {
	local ps_output
	ps_output="$(ps -ww -eo pid=,args= --sort=-pid)"
	[[ -n "${ps_output}" ]] || return 0

	local line trimmed pid argv shim_id namespace address debug_socket main_listener debug_listener token prev
	while IFS= read -r line; do
		[[ -n "${line}" ]] || continue
		trimmed="${line#"${line%%[![:space:]]*}"}"
		[[ -n "${trimmed}" ]] || continue
		read -r pid argv <<<"${trimmed}"
		[[ -n "${pid}" && -n "${argv}" ]] || continue
		[[ "${argv}" == *flox-runtime-v2* ]] || continue

		shim_id=""
		namespace="${DEFAULT_CONTAINERD_NAMESPACE}"
		address="${DEFAULT_CONTAINERD_ADDRESS}"
		debug_socket="-"
		prev=""

		for token in ${argv}; do
			case "${prev}" in
			-id)
				shim_id="${token}"
				;;
			-namespace)
				namespace="${token}"
				;;
			-address)
				address="${token}"
				;;
			-debug-socket)
				debug_socket="${token}"
				;;
			esac
			prev="${token}"
		done

		main_listener="-"
		debug_listener="-"
		if [[ "${shim_id}" =~ ^[0-9a-f]{64}$ ]]; then
			main_listener="$(hash_logical_path "${address}/${namespace}/${shim_id}")"
			debug_listener="$(hash_logical_path "${address}/${namespace}/${shim_id}/debug")"
		fi

		printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
			"${pid}" \
			"${shim_id:--}" \
			"${namespace:--}" \
			"${address:--}" \
			"${debug_socket:--}" \
			"${main_listener:--}" \
			"${debug_listener:--}" \
			"${argv:--}"
	done < <(printf '%s\n' "${ps_output}")
}

print_live_containerd_shim_flox_v2s() {
	local rows
	rows="$(collect_live_containerd_shim_flox_v2s)"
	if [[ -z "${rows}" ]]; then
		echo "no live flox-runtime-v2 processes found on this host"
		return 0
	fi

	printf 'host=%s\n' "$(hostname)"
	printf '%-8s %-64s %-8s %s\n' "pid" "shim-id" "ns" "debug-listener"
	while IFS=$'\t' read -r pid shim_id namespace address debug_socket main_listener debug_listener argv; do
		printf '%-8s %-64s %-8s %s\n' "${pid}" "${shim_id}" "${namespace}" "${debug_listener:-<missing>}"
		printf '  address=%s\n' "${address}"
		printf '  debug-socket=%s\n' "${debug_socket:-<missing>}"
		printf '  main-listener=%s\n' "${main_listener:-<missing>}"
		printf '  argv=%s\n' "${argv}"
	done <<<"${rows}"
}

select_live_shim() {
	local selector="${1:-}"
	local rows matches match_count
	rows="$(collect_live_containerd_shim_flox_v2s)"
	[[ -n "${rows}" ]] || die "no live flox-runtime-v2 processes found on this host"

	if [[ -z "${selector}" ]]; then
		printf '%s\n' "${rows}" | head -n 1
		return 0
	fi

	matches="$(printf '%s\n' "${rows}" | awk -F '\t' -v selector="${selector}" '($2 == selector) || (index($2, selector) == 1) { print }')"
	[[ -n "${matches}" ]] || die "no live Flox shim matches selector '${selector}'"

	match_count="$(printf '%s\n' "${matches}" | awk 'NF { count++ } END { print count + 0 }')"
	if [[ "${match_count}" -gt 1 ]]; then
		printf 'matching live flox-runtime-v2 processes for selector %q:\n' "${selector}" >&2
		printf '%s\n' "${matches}" | awk -F '\t' '{ printf "  pid=%s id=%s\n", $1, $2 }' >&2
		die "selector '${selector}' is ambiguous"
	fi

	printf '%s\n' "${matches}"
}

run_pprof() {
	local pprof_command="$1"
	shift
	local ctr_bin
	ctr_bin="$(resolve_remote_ctr)"
	local pprof_timeout="${DEFAULT_PPROF_TIMEOUT}"

	local selector=""
	if [[ $# -gt 0 && "$1" != --* ]]; then
		selector="$1"
		shift
	fi

	while [[ $# -gt 0 ]]; do
		case "$1" in
		--timeout)
			[[ $# -ge 2 ]] || die "--timeout requires a value"
			pprof_timeout="$2"
			shift 2
			;;
		*)
			break
			;;
		esac
	done

	local target_line pid shim_id namespace address debug_socket main_listener debug_listener argv
	target_line="$(select_live_shim "${selector}")"
	IFS=$'\t' read -r pid shim_id namespace address debug_socket main_listener debug_listener argv <<<"${target_line}"

	if [[ ${#shim_id} -ne 64 ]]; then
		die "selected shim id appears truncated (${shim_id}); rerun after verifying ps output is not truncated"
	fi

	printf 'selected shim pid=%s id=%s namespace=%s\n' "${pid}" "${shim_id}" "${namespace}" >&2
	printf '  address=%s\n' "${address}" >&2
	printf '  debug-socket=%s\n' "${debug_socket:-<missing>}" >&2
	printf '  main-listener=%s\n' "${main_listener:-<missing>}" >&2
	printf '  debug-listener=%s\n' "${debug_listener:-<missing>}" >&2
	printf '  timeout=%s\n' "${pprof_timeout}" >&2

	local ec=0
	run_with_timeout "${pprof_timeout}" "${ctr_bin}" --address "${address}" --namespace "${namespace}" shim --id "${shim_id}" pprof "${pprof_command}" "$@" || ec=$?
	if [[ "${ec}" -ne 0 ]]; then
		if [[ "${ec}" -eq 124 ]]; then
			die "pprof command timed out after ${pprof_timeout}; rerun with --timeout 0 to disable or a larger duration"
		fi
		return "${ec}"
	fi
}

while [[ $# -gt 0 ]]; do
	case "$1" in
	--address)
		[[ $# -ge 2 ]] || die "--address requires a value"
		DEFAULT_CONTAINERD_ADDRESS="$2"
		shift 2
		;;
	--namespace)
		[[ $# -ge 2 ]] || die "--namespace requires a value"
		DEFAULT_CONTAINERD_NAMESPACE="$2"
		shift 2
		;;
	--flox-dir)
		[[ $# -ge 2 ]] || die "--flox-dir requires a value"
		REMOTE_FLOX_DIR="$2"
		shift 2
		;;
	--timeout)
		[[ $# -ge 2 ]] || die "--timeout requires a value"
		DEFAULT_PPROF_TIMEOUT="$2"
		shift 2
		;;
	-h | --help)
		usage
		exit 0
		;;
	--)
		shift
		break
		;;
	*)
		break
		;;
	esac
done

activate_remote_env

command_name="${1:-list}"
if [[ $# -gt 0 ]]; then
	shift
fi

case "${command_name}" in
list)
	print_live_containerd_shim_flox_v2s
	;;
goroutines | heap | profile | trace | block | threadcreate)
	run_pprof "${command_name}" "$@"
	;;
*)
	die "unknown command '${command_name}'"
	;;
esac
