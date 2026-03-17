#!/usr/bin/env bash

# shellcheck shell=bash

# Configure script stderr mirroring to a colocated log file.
# Log path policy: <script-directory>/<script-basename>.log (no append).
daemonset::logging:stderr:setup() {
  local script_path="${1:?script path required}"
  local script_dir script_base script_log

  script_dir="$(dirname "${script_path}")"
  script_base="$(basename "${script_path}")"
  script_log="${script_dir}/${script_base}.log"

  mkdir -p "${script_dir}"
  exec 2> >(tee "${script_log}" >&2)
}
