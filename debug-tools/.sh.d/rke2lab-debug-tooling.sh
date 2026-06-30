#!/usr/bin/env bash

rke2lab::debug:bool:is_true() {
	case "${1:-}" in
	1 | true | TRUE | yes | YES | on | ON)
		return 0
		;;
	*)
		return 1
		;;
	esac
}

rke2lab::debug:script_dir() {
	local script_path="${1:-$0}"
	cd -- "$(dirname -- "${script_path}")" && pwd
}

rke2lab::debug:flox:activate_if_present() {
	local flox_dir="${1:-/var/lib/rancher/rke2}"

	if command -v flox >/dev/null 2>&1 && [[ -d "${flox_dir}" ]]; then
		# shellcheck disable=SC1091
		set +x # Silence flox activation noise
		source <(flox activate --dir "${flox_dir}")
		set -x
	fi
}

rke2lab::debug:logging:setup() {
	if rke2lab::debug:bool:is_true "${RKE2LAB_DEBUG_AUTO_LOG_INITIALIZED:-0}"; then
		return 0
	fi

	if ! rke2lab::debug:bool:is_true "${RKE2LAB_DEBUG_AUTO_LOG_ENABLED:-1}"; then
		return 0
	fi

	local script_path="${1:-$0}"
	local script_dir
	local script_name
	local script_stem
	local log_mode
	local log_file
	local -a tee_args=()

	script_dir="$(rke2lab::debug:script_dir "${script_path}")"
	script_name="$(basename -- "${script_path}")"
	script_stem="${script_name%.sh}"
	log_mode="${LOG_MODE:-truncate}"
	log_file="${LOG_FILE:-${script_dir%/}/${script_stem}.log}"

	mkdir -p "$(dirname -- "${log_file}")"
	case "${log_mode}" in
	truncate)
		: >"${log_file}"
		;;
	append)
		tee_args=(-a)
		;;
	*)
		echo "unsupported LOG_MODE=${log_mode}; expected 'truncate' or 'append'" >&2
		exit 1
		;;
	esac

	export LOG_FILE="${log_file}"
	export RKE2LAB_DEBUG_AUTO_LOG_INITIALIZED=1
	exec > >(tee "${tee_args[@]}" "${log_file}") 2>&1

	printf '[%s] auto-log: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${log_file}"
}
