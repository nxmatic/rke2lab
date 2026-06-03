#!/usr/bin/env bash

# shellcheck shell=bash

# Canonical daemonset host-shell policy:
# - executable shell entrypoints live under <asset-root>/bin
# - sourced shell helper files live under <asset-root>/.sh.d
# - configuration files live under <asset-root>/etc
# - script logs/xtrace live under <asset-root>/log
#
# Canonical environment contract:
# - DAEMONSET_HOST_SCRIPT_ROOT=/srv/host/... (required for host-side resolution)
# - DAEMONSET_HOST_SCRIPT_BIN=/srv/host/.../bin (defaults to <root>/bin)
# - DAEMONSET_HOST_SCRIPT_LIB_DIR=/srv/host/.../.sh.d (defaults to <root>/.sh.d)
# - DAEMONSET_HOST_SCRIPT_ETC_DIR=/srv/host/.../etc (defaults to <root>/etc)
# - DAEMONSET_SCRIPT_LOG_DIR=/srv/host/.../log (defaults to <root>/log)
#
# Use daemonset::host_shell:binary:install for host-reexec-capable shell entrypoints.
# Use daemonset::host_shell:library:install for sourced shell helper files.

daemonset::host_shell:root:resolve() {
	local shell_root="${DAEMONSET_HOST_SCRIPT_ROOT:-}"

	[[ -n "${shell_root}" ]] || {
		echo "DAEMONSET_HOST_SCRIPT_ROOT is required for daemonset host shell policy" >&2
		return 1
	}

	printf '%s\n' "${shell_root}"
}

daemonset::host_shell:bin:resolve() {
	local shell_root shell_bin

	shell_root="$(daemonset::host_shell:root:resolve)" || return 1
	shell_bin="${DAEMONSET_HOST_SCRIPT_BIN:-${shell_root%/}/bin}"

	printf '%s\n' "${shell_bin}"
}

daemonset::host_shell:library:resolve() {
	local shell_root shell_library_dir

	shell_root="$(daemonset::host_shell:root:resolve)" || return 1
	shell_library_dir="${DAEMONSET_HOST_SCRIPT_LIB_DIR:-${shell_root%/}/.sh.d}"

	printf '%s\n' "${shell_library_dir}"
}

daemonset::host_shell:etc:resolve() {
	local shell_root shell_etc_dir

	shell_root="$(daemonset::host_shell:root:resolve)" || return 1
	shell_etc_dir="${DAEMONSET_HOST_SCRIPT_ETC_DIR:-${shell_root%/}/etc}"

	printf '%s\n' "${shell_etc_dir}"
}

daemonset::host_shell:log:resolve() {
	local shell_root shell_log_dir

	shell_root="$(daemonset::host_shell:root:resolve)" || return 1
	shell_log_dir="${DAEMONSET_SCRIPT_LOG_DIR:-${shell_root%/}/log}"

	printf '%s\n' "${shell_log_dir}"
}

daemonset::host_shell:layout:ensure() {
	local shell_root="${1:?shell root required}"
	local shell_bin shell_etc shell_log shell_lib

	shell_bin="${DAEMONSET_HOST_SCRIPT_BIN:-${shell_root%/}/bin}"
	shell_etc="${DAEMONSET_HOST_SCRIPT_ETC_DIR:-${shell_root%/}/etc}"
	shell_log="${DAEMONSET_SCRIPT_LOG_DIR:-${shell_root%/}/log}"
	shell_lib="${DAEMONSET_HOST_SCRIPT_LIB_DIR:-${shell_root%/}/.sh.d}"

	mkdir -p "${shell_root}" "${shell_bin}" "${shell_etc}" "${shell_log}" "${shell_lib}"
}

daemonset::host_shell:executable:install() {
	local source_path="${1:?source path required}"
	local shell_root="${2:?shell root required}"
	local target_relative_path="${3:?target relative path required}"
	local install_mode="${4:-0755}"
	local shell_bin target_path

	[[ -r "${source_path}" ]] || {
		echo "host shell executable source missing or unreadable: ${source_path}" >&2
		return 1
	}

	daemonset::host_shell:layout:ensure "${shell_root}"
	shell_bin="${DAEMONSET_HOST_SCRIPT_BIN:-${shell_root%/}/bin}"
	target_path="${shell_bin%/}/${target_relative_path}"
	install -D -m "${install_mode}" "${source_path}" "${target_path}"
	printf '%s\n' "${target_path}"
}

daemonset::host_shell:config:install() {
	local source_path="${1:?source path required}"
	local shell_root="${2:?shell root required}"
	local target_relative_path="${3:?target relative path required}"
	local install_mode="${4:-0644}"
	local shell_etc target_path

	[[ -r "${source_path}" ]] || {
		echo "host shell config source missing or unreadable: ${source_path}" >&2
		return 1
	}

	daemonset::host_shell:layout:ensure "${shell_root}"
	shell_etc="${DAEMONSET_HOST_SCRIPT_ETC_DIR:-${shell_root%/}/etc}"
	target_path="${shell_etc%/}/${target_relative_path}"
	install -D -m "${install_mode}" "${source_path}" "${target_path}"
	printf '%s\n' "${target_path}"
}

daemonset::host_shell:library:dir() {
	local shell_root="${1:?shell root required}"
	printf '%s/.sh.d\n' "${shell_root%/}"
}

daemonset::host_shell:library:ensure() {
	local shell_root="${1:?shell root required}"
	local library_dir

	library_dir="$(daemonset::host_shell:library:dir "${shell_root}")"
	mkdir -p "${library_dir}"
	printf '%s\n' "${library_dir}"
}

daemonset::host_shell:library:install() {
	local source_path="${1:?source path required}"
	local shell_root="${2:?shell root required}"
	local target_relative_path="${3:?target relative path required}"
	local install_mode="${4:-0644}"
	local library_dir target_path

	[[ -r "${source_path}" ]] || {
		echo "host shell library source missing or unreadable: ${source_path}" >&2
		return 1
	}

	library_dir="$(daemonset::host_shell:library:ensure "${shell_root}")" || return 1
	target_path="${library_dir%/}/${target_relative_path}"
	install -D -m "${install_mode}" "${source_path}" "${target_path}"
	printf '%s\n' "${target_path}"
}

daemonset::host_shell:binary:install() {
	daemonset::host_shell:executable:install "$@"
}
