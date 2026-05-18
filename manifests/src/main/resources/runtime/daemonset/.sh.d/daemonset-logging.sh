#!/usr/bin/env bash

# shellcheck shell=bash

# Configure script stderr mirroring to a colocated xtrace file.
# Trace path policy: <script-directory>/<script-basename>.xtrace (no append).
daemonset::logging:stderr:setup() {
	local script_path="${1:?script path required}"
	local script_dir script_base script_trace

	script_dir="$(dirname "${script_path}")"
	script_base="$(basename "${script_path}")"
	script_trace="${script_dir}/${script_base}.xtrace"

	mkdir -p "${script_dir}"
	exec 2> >(tee "${script_trace}" >&2)
}
