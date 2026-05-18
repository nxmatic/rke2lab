#!/usr/bin/env bash

# shellcheck shell=bash

# Canonical daemonless host-shell policy:
# - executable shell entrypoints live under <asset-root>/bin
# - sourced shell helper files live under <asset-root>/.sh.d
# - configuration files live under <asset-root>/etc
# - script logs/xtrace live under <asset-root>/log
#
# Canonical environment contract:
# - DAEMONLESS_HOST_SCRIPT_ROOT=/srv/host/... (required for host-side resolution)
# - DAEMONLESS_HOST_SCRIPT_BIN=/srv/host/.../bin (defaults to <root>/bin)
# - DAEMONLESS_HOST_SCRIPT_LIB_DIR=/srv/host/.../.sh.d (defaults to <root>/.sh.d)
# - DAEMONLESS_HOST_SCRIPT_ETC_DIR=/srv/host/.../etc (defaults to <root>/etc)
# - DAEMONSET_SCRIPT_LOG_DIR=/srv/host/.../log (defaults to <root>/log)
#
# Use daemonless::host_shell:binary:install for host-reexec-capable shell entrypoints.
# Use daemonless::host_shell:library:install for sourced shell helper files.

daemonless::host_shell:root:resolve() {
	local shell_root="${DAEMONLESS_HOST_SCRIPT_ROOT:-}"

	[[ -n "${shell_root}" ]] || {
		echo "DAEMONLESS_HOST_SCRIPT_ROOT is required for daemonless host shell policy" >&2
		return 1
	}

	printf '%s\n' "${shell_root}"
}

daemonless::host_shell:bin:resolve() {
	local shell_root shell_bin

	shell_root="$(daemonless::host_shell:root:resolve)" || return 1
	shell_bin="${DAEMONLESS_HOST_SCRIPT_BIN:-${shell_root%/}/bin}"

	printf '%s\n' "${shell_bin}"
}

daemonless::host_shell:library:resolve() {
	local shell_root shell_library_dir

	shell_root="$(daemonless::host_shell:root:resolve)" || return 1
	shell_library_dir="${DAEMONLESS_HOST_SCRIPT_LIB_DIR:-${shell_root%/}/.sh.d}"

	printf '%s\n' "${shell_library_dir}"
}

daemonless::host_shell:etc:resolve() {
	local shell_root shell_etc_dir

	shell_root="$(daemonless::host_shell:root:resolve)" || return 1
	shell_etc_dir="${DAEMONLESS_HOST_SCRIPT_ETC_DIR:-${shell_root%/}/etc}"

	printf '%s\n' "${shell_etc_dir}"
}

daemonless::host_shell:log:resolve() {
	local shell_root shell_log_dir

	shell_root="$(daemonless::host_shell:root:resolve)" || return 1
	shell_log_dir="${DAEMONSET_SCRIPT_LOG_DIR:-${shell_root%/}/log}"

	printf '%s\n' "${shell_log_dir}"
}

daemonless::host_shell:layout:ensure() {
	local shell_root="${1:?shell root required}"
	local shell_bin shell_etc shell_log shell_lib

	shell_bin="${DAEMONLESS_HOST_SCRIPT_BIN:-${shell_root%/}/bin}"
	shell_etc="${DAEMONLESS_HOST_SCRIPT_ETC_DIR:-${shell_root%/}/etc}"
	shell_log="${DAEMONSET_SCRIPT_LOG_DIR:-${shell_root%/}/log}"
	shell_lib="${DAEMONLESS_HOST_SCRIPT_LIB_DIR:-${shell_root%/}/.sh.d}"

	mkdir -p "${shell_root}" "${shell_bin}" "${shell_etc}" "${shell_log}" "${shell_lib}"
}

daemonless::host_shell:executable:install() {
	local source_path="${1:?source path required}"
	local shell_root="${2:?shell root required}"
	local target_relative_path="${3:?target relative path required}"
	local install_mode="${4:-0755}"
	local shell_bin target_path

	[[ -r "${source_path}" ]] || {
		echo "host shell executable source missing or unreadable: ${source_path}" >&2
		return 1
	}

	daemonless::host_shell:layout:ensure "${shell_root}"
	shell_bin="${DAEMONLESS_HOST_SCRIPT_BIN:-${shell_root%/}/bin}"
	target_path="${shell_bin%/}/${target_relative_path}"
	install -D -m "${install_mode}" "${source_path}" "${target_path}"
	printf '%s\n' "${target_path}"
}

daemonless::host_shell:config:install() {
	local source_path="${1:?source path required}"
	local shell_root="${2:?shell root required}"
	local target_relative_path="${3:?target relative path required}"
	local install_mode="${4:-0644}"
	local shell_etc target_path

	[[ -r "${source_path}" ]] || {
		echo "host shell config source missing or unreadable: ${source_path}" >&2
		return 1
	}

	daemonless::host_shell:layout:ensure "${shell_root}"
	shell_etc="${DAEMONLESS_HOST_SCRIPT_ETC_DIR:-${shell_root%/}/etc}"
	target_path="${shell_etc%/}/${target_relative_path}"
	install -D -m "${install_mode}" "${source_path}" "${target_path}"
	printf '%s\n' "${target_path}"
}

daemonless::host_shell:library:dir() {
	local shell_root="${1:?shell root required}"
	printf '%s/.sh.d\n' "${shell_root%/}"
}

daemonless::host_shell:library:ensure() {
	local shell_root="${1:?shell root required}"
	local library_dir

	library_dir="$(daemonless::host_shell:library:dir "${shell_root}")"
	mkdir -p "${library_dir}"
	printf '%s\n' "${library_dir}"
}

daemonless::host_shell:library:install() {
	local source_path="${1:?source path required}"
	local shell_root="${2:?shell root required}"
	local target_relative_path="${3:?target relative path required}"
	local install_mode="${4:-0644}"
	local library_dir target_path

	[[ -r "${source_path}" ]] || {
		echo "host shell library source missing or unreadable: ${source_path}" >&2
		return 1
	}

	library_dir="$(daemonless::host_shell:library:ensure "${shell_root}")" || return 1
	target_path="${library_dir%/}/${target_relative_path}"
	install -D -m "${install_mode}" "${source_path}" "${target_path}"
	printf '%s\n' "${target_path}"
}

daemonless::host_shell:binary:install() {
	daemonless::host_shell:executable:install "$@"
}
