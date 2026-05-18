#!/usr/bin/env bash

# shellcheck shell=bash

# Canonical daemonless host-shell policy:
# - executable shell entrypoints delegate to the generic host-binary policy
# - sourced shell helper files live under <asset-root>/.sh.d
#
# Canonical environment contract:
# - DAEMONLESS_HOST_SCRIPT_ROOT=/srv/host/... (required for host-side resolution)
# - DAEMONLESS_HOST_SCRIPT_BIN=/srv/host/.../bin (defaults to <root>/bin)
# - DAEMONLESS_HOST_SCRIPT_LIB_DIR=/srv/host/.../.sh.d (defaults to <root>/.sh.d)
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
	daemonless::host_binary:install "$@"
}
