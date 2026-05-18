#!/usr/bin/env bash

# shellcheck shell=bash

# Generic daemonless trampoline policy for scripts that may start in a pod or guest context
# but need to re-exec on the host.
#
# Canonical environment contract:
# - DAEMONLESS_EXEC_MODE=host|guest|pod
# - DAEMONLESS_HOST_SCRIPT_ROOT=/srv/host/... (required for pod/guest re-exec)
# - DAEMONLESS_HOST_SCRIPT_BIN=/srv/host/.../bin (defaults to <root>/bin)
# - DAEMONLESS_HOST_SSH_TARGET=<ssh target> (required only for guest->host re-exec)

daemonless::trampoline:mode:resolve() {
	local mode="${DAEMONLESS_EXEC_MODE:-guest}"

	case "${mode}" in
	host | guest | pod)
		printf '%s\n' "${mode}"
		return 0
		;;
	*)
		echo "unsupported daemonless execution mode: ${mode}" >&2
		return 1
		;;
	esac
}

daemonless::trampoline:host_script_root:resolve() {
	local root="${DAEMONLESS_HOST_SCRIPT_ROOT:-}"
	[[ -n "${root}" ]] || {
		echo "DAEMONLESS_HOST_SCRIPT_ROOT is required for daemonless trampoline re-exec" >&2
		return 1
	}
	printf '%s\n' "${root}"
}

daemonless::trampoline:host_script_bin:path() {
	local host_script_root host_script_bin

	host_script_root="$(daemonless::trampoline:host_script_root:resolve)" || return 1
	host_script_bin="${DAEMONLESS_HOST_SCRIPT_BIN:-${host_script_root%/}/bin}"
	printf '%s\n' "${host_script_bin}"
}

daemonless::trampoline:host_script_bin:resolve() {
	local host_script_bin

	host_script_bin="$(daemonless::trampoline:host_script_bin:path)" || return 1
	[[ -d "${host_script_bin}" ]] || {
		echo "daemonless host script bin directory not found: ${host_script_bin}" >&2
		return 1
	}

	printf '%s\n' "${host_script_bin}"
}

daemonless::trampoline:host_command_path:path() {
	local script_name="$1"
	local host_script_bin

	host_script_bin="$(daemonless::trampoline:host_script_bin:path)" || return 1
	printf '%s\n' "${host_script_bin%/}/${script_name}"
}

daemonless::trampoline:host_command_path() {
	local script_name="$1"
	local host_script_bin host_command_path

	host_script_bin="$(daemonless::trampoline:host_script_bin:resolve)" || return 1
	host_command_path="${host_script_bin%/}/${script_name}"
	[[ -x "${host_command_path}" ]] || {
		echo "daemonless host command not found or not executable: ${host_command_path}" >&2
		return 1
	}

	printf '%s\n' "${host_command_path}"
}

daemonless::trampoline:exec_on_host() {
	local script_name="$1"
	shift

	local mode host_script_bin host_command_path ssh_target remote_command env_pair arg
	local -a env_pairs=()
	local -a script_args=()

	mode="$(daemonless::trampoline:mode:resolve)" || return 1

	while [[ $# -gt 0 && "$1" == *=* ]]; do
		env_pairs+=("$1")
		shift
	done
	script_args=("$@")

	case "${mode}" in
	host)
		host_script_bin="$(daemonless::trampoline:host_script_bin:resolve)" || return 1
		host_command_path="$(daemonless::trampoline:host_command_path "${script_name}")" || return 1
		echo "daemonless host trampoline should not be used when already on host" >&2
		return 1
		;;
	pod)
		command -v nsenter >/dev/null 2>&1 || {
			echo "nsenter is required for daemonless pod->host trampoline" >&2
			return 1
		}
		host_script_bin="$(daemonless::trampoline:host_script_bin:path)" || return 1
		host_command_path="$(daemonless::trampoline:host_command_path:path "${script_name}")" || return 1
		exec nsenter --target 1 --mount --uts --ipc --net --pid -- env \
			DAEMONLESS_EXEC_MODE=host \
			DAEMONLESS_HOST_SCRIPT_ROOT="${DAEMONLESS_HOST_SCRIPT_ROOT}" \
			DAEMONLESS_HOST_SCRIPT_BIN="${host_script_bin}" \
			PATH="${host_script_bin}:${PATH}" \
			"${env_pairs[@]}" \
			bash -x "${host_command_path}" "${script_args[@]}"
		;;
	guest)
		ssh_target="${DAEMONLESS_HOST_SSH_TARGET:-}"
		[[ -n "${ssh_target}" ]] || {
			echo "DAEMONLESS_HOST_SSH_TARGET is required for daemonless guest->host trampoline" >&2
			return 1
		}
		host_script_bin="$(daemonless::trampoline:host_script_bin:path)" || return 1
		host_command_path="$(daemonless::trampoline:host_command_path:path "${script_name}")" || return 1

		remote_command="env"
		remote_command+=" $(printf '%q' 'DAEMONLESS_EXEC_MODE=host')"
		remote_command+=" $(printf '%q' "DAEMONLESS_HOST_SCRIPT_ROOT=${DAEMONLESS_HOST_SCRIPT_ROOT}")"
		remote_command+=" $(printf '%q' "DAEMONLESS_HOST_SCRIPT_BIN=${host_script_bin}")"
		remote_command+=" $(printf '%q' "PATH=${host_script_bin}:${PATH}")"
		for env_pair in "${env_pairs[@]}"; do
			remote_command+=" $(printf '%q' "${env_pair}")"
		done
		remote_command+=" bash -x $(printf '%q' "${host_command_path}")"
		for arg in "${script_args[@]}"; do
			remote_command+=" $(printf '%q' "${arg}")"
		done

		exec ssh -n "${ssh_target}" -- "${remote_command}"
		;;
	esac
}
