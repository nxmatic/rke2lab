#!/bin/bash
set -exuo pipefail

# Flox shim package build script
# Builds packages from local packaged flakes defined in YAML descriptor
# Usage: flox-shim-build.sh [mode] [descriptor file]
# Set FLOX_SHIM_ONLY_UPDATE_LOCKS=true to refresh flake.lock files only.

SCRIPT_PATH="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="${SCRIPT_PATH%/*}"
if [[ "${SCRIPT_DIR}" == "${SCRIPT_PATH}" ]]; then
	SCRIPT_DIR='.'
fi
SCRIPT_BASENAME="$(basename "${SCRIPT_PATH}")"
SCRIPT_STEM="${SCRIPT_BASENAME%.sh}"
WORKTREE_MODE="${FLOX_SHIM_MODE:-guest}"

canonicalize_existing_path() {
	local input_path="$1"
	if [[ ! -e "${input_path}" ]]; then
		echo "${input_path}"
		return 0
	fi

	if [[ -d "${input_path}" ]]; then
		(
			cd "${input_path}"
			pwd -P
		)
		return 0
	fi

	local input_dir input_base
	input_dir="$(dirname "${input_path}")"
	input_base="$(basename "${input_path}")"
	echo "$(cd "${input_dir}" && pwd -P)/${input_base}"
}

if [[ $# -gt 0 ]]; then
	case "${1}" in
	host | guest)
		WORKTREE_MODE="${1}"
		shift
		;;
	esac
fi

BUILDS_DESCRIPTOR="${1:-${SCRIPT_DIR}/${SCRIPT_STEM}.yaml}"
BUILDS_DESCRIPTOR="$(canonicalize_existing_path "${BUILDS_DESCRIPTOR}")"
BUILDS_DESCRIPTOR_DIR="$(dirname "${BUILDS_DESCRIPTOR}")"
BUILDS_DESCRIPTOR_DIR="$(canonicalize_existing_path "${BUILDS_DESCRIPTOR_DIR}")"

# Environment variables
RKE2LAB_ROOT="${RKE2LAB_ROOT:-/srv/host}"
UPDATED_FLAKE_LOCK_DIRS=""

rke2lab::bool:is_true() {
	case "${1:-}" in
	1 | true | TRUE | yes | YES | on | ON)
		return 0
		;;
	*)
		return 1
		;;
	esac
}

rke2lab::env:load() {
	local env_script

	env_script="${RKE2LAB_SCRIPTS_DIR:-${RKE2LAB_ROOT}/systemd-scripts.d}/rke2lab-env-load.sh"
	[[ -r "${env_script}" ]] || return 0

	# shellcheck disable=SC1090
	source "${env_script}"
	declare -F rke2lab::env:load >/dev/null 2>&1 && rke2lab::env:load
}

resolve_package_variant() {
	local package_name="$1"
	local package_attr="$2"

	case "${package_name}" in
	kdns)
		if rke2lab::bool:is_true "${RKE2LAB_POLICY_DEBUG_KDNS_ENABLED:-false}"; then
			package_name="kdns-debug"
			[[ "${package_attr}" == *-debug ]] || package_attr="${package_attr}-debug"
		fi
		;;
	flox-shim-wrapper)
		if rke2lab::bool:is_true "${RKE2LAB_POLICY_DEBUG_FLOX_SHIM_WRAPPER_ENABLED:-false}"; then
			package_name="flox-shim-wrapper-debug"
			[[ "${package_attr}" == *-debug ]] || package_attr="${package_attr}-debug"
		fi
		;;
	esac

	printf '%s\n%s\n' "${package_name}" "${package_attr}"
}

validate_worktree_mode() {
	case "${WORKTREE_MODE}" in
	host | guest)
		return 0
		;;
	*)
		: "[ERROR] Unsupported or missing shim builder mode: '${WORKTREE_MODE}'"
		: "[ERROR] Usage: ${SCRIPT_BASENAME} [host|guest] [descriptor file]"
		: "[ERROR] Default mode is 'guest' when omitted."
		: "[ERROR] You can also set FLOX_SHIM_MODE and pass only [descriptor file]."
		exit 1
		;;
	esac
}

find_flox_activation_dir() {
	local -a candidates=()

	if [[ -n "${FLOX_SHIM_ENV_DIR:-}" ]]; then
		candidates+=("${FLOX_SHIM_ENV_DIR}")
	fi

	if [[ "${WORKTREE_MODE}" == "host" ]]; then
		candidates+=("/var/lib/rancher/rke2")
	fi

	candidates+=(
		"/var/lib/git/nxmatic/rke2lab"
		"/srv/host/git-worktree.d/nxmatic/rke2lab"
	)

	local candidate
	for candidate in "${candidates[@]}"; do
		[[ -n "${candidate}" ]] || continue
		[[ -d "${candidate}" ]] || continue
		if [[ -d "${candidate}/.flox" || -f "${candidate}/flake.nix" ]]; then
			echo "${candidate}"
			return 0
		fi
	done

	return 1
}

activate_flox_environment() {
	if ! command -v flox >/dev/null 2>&1; then
		: "[WARN] flox command is unavailable; continuing with existing shell environment"
		return 0
	fi

	local activation_dir
	if ! activation_dir="$(find_flox_activation_dir)"; then
		: "[WARN] No Flox activation directory found for mode '${WORKTREE_MODE}'; continuing with existing shell environment"
		return 0
	fi

	if source <(flox activate --dir "${activation_dir}"); then
		: "[$(date)] Activated Flox environment from: ${activation_dir}"
		return 0
	fi

	: "[WARN] Failed to activate Flox environment from '${activation_dir}'; continuing with existing shell environment"
	return 0
}

is_flake_lock_refreshed() {
	local resolved_path="$1"
	[[ $'\n'"${UPDATED_FLAKE_LOCK_DIRS}"$'\n' == *$'\n'"${resolved_path}"$'\n'* ]]
}

mark_flake_lock_refreshed() {
	local resolved_path="$1"
	UPDATED_FLAKE_LOCK_DIRS="${UPDATED_FLAKE_LOCK_DIRS}"$'\n'"${resolved_path}"
}

resolve_user_log_root() {
	if [[ -n "${XDG_STATE_HOME:-}" ]]; then
		echo "${XDG_STATE_HOME}/rke2lab"
		return 0
	fi

	if [[ -n "${HOME:-}" ]]; then
		echo "${HOME}/.local/state/rke2lab"
		return 0
	fi

	echo "/tmp/rke2lab"
}

ensure_directory_writable() {
	local dir_path="$1"
	mkdir -p "${dir_path}" 2>/dev/null || return 1
	[[ -w "${dir_path}" ]]
}

resolve_log_file_path() {
	local requested_log_file="${1-}"
	local job_name="${2-unnamed-job}"
	local log_basename="rke2-${job_name}-build.log"

	if [[ -n "${requested_log_file}" ]]; then
		local requested_basename
		requested_basename="$(basename "${requested_log_file}")"
		if [[ -n "${requested_basename}" ]]; then
			log_basename="${requested_basename}"
		fi
	fi

	if [[ -n "${requested_log_file}" ]]; then
		local requested_dir
		requested_dir="$(dirname "${requested_log_file}")"
		if ensure_directory_writable "${requested_dir}"; then
			local resolved_system_log
			resolved_system_log="$(canonicalize_existing_path "${requested_log_file}")"
			: "[$(date)] [${job_name}] Log scope: system-wide (${resolved_system_log})"
			echo "${resolved_system_log}"
			return 0
		fi
	fi

	local user_log_root
	user_log_root="$(resolve_user_log_root)"
	if ensure_directory_writable "${user_log_root}"; then
		local resolved_user_log
		resolved_user_log="$(canonicalize_existing_path "${user_log_root}")/${log_basename}"
		: "[$(date)] [${job_name}] Log scope: user-wide XDG (${resolved_user_log})"
		echo "${resolved_user_log}"
		return 0
	fi

	local tmp_log="/tmp/${log_basename}"
	: "[$(date)] [${job_name}] Log scope: user fallback (${tmp_log})"
	echo "${tmp_log}"
}

: "[$(date)] Starting flox shim package builds from descriptor: ${BUILDS_DESCRIPTOR}"

# Check if descriptor exists
if [[ ! -f "${BUILDS_DESCRIPTOR}" ]]; then
	: "[ERROR] Builds descriptor not found: ${BUILDS_DESCRIPTOR}"
	exit 1
fi

# Resolve local flake path from descriptor directory when relative
resolve_flake_path() {
	local flake_path="$1"
	local resolved_path

	if [[ "${flake_path}" == /* ]]; then
		resolved_path="${flake_path}"
	else
		resolved_path="${BUILDS_DESCRIPTOR_DIR}/${flake_path}"
	fi

	echo "$(canonicalize_existing_path "${resolved_path}")"
	return 0
}

should_update_locks() {
	case "${FLOX_SHIM_UPDATE_LOCKS:-auto}" in
	1 | true | TRUE | yes | YES)
		return 0
		;;
	0 | false | FALSE | no | NO)
		return 1
		;;
	auto | AUTO | "")
		[[ "${WORKTREE_MODE}" == "host" ]]
		return
		;;
	*)
		: "[WARN] Unknown FLOX_SHIM_UPDATE_LOCKS='${FLOX_SHIM_UPDATE_LOCKS}', defaulting to auto"
		[[ "${WORKTREE_MODE}" == "host" ]]
		return
		;;
	esac
}

should_only_update_locks() {
	case "${FLOX_SHIM_ONLY_UPDATE_LOCKS:-false}" in
	1 | true | TRUE | yes | YES)
		return 0
		;;
	*)
		return 1
		;;
	esac
}

refresh_flake_lock_if_needed() {
	local job_name="$1"
	local resolved_path="$2"
	local log_file="$3"

	should_update_locks || return 0

	if is_flake_lock_refreshed "${resolved_path}"; then
		return 0
	fi

	local -a lock_args=(
		"flake"
		"lock"
		"--extra-experimental-features" "nix-command"
		"--extra-experimental-features" "flakes"
	)
	: "[$(date)] [${job_name}] Refreshing flake.lock"

	if (
		cd "${resolved_path}"
		nix "${lock_args[@]}"
	) 2>&1 | tee "${log_file}"; then
		mark_flake_lock_refreshed "${resolved_path}"
		: "[$(date)] [${job_name}] ✓ Refreshed flake.lock at ${resolved_path}"
		return 0
	fi

	: "[ERROR] [${job_name}] Failed to refresh flake.lock at ${resolved_path}"
	return 1
}

# Function to build a single package
build_package() {
	local job_name="$1"
	local flake_path="$2"
	local output_dir="$3"
	local log_file="$4"
	local package_name="$5"
	local package_attr="$6"

	: "[$(date)] [${job_name}] Building ${package_name}..."

	if [[ -z "${flake_path}" ]]; then
		: "[ERROR] [${job_name}] flakePath is empty"
		return 1
	fi

	local resolved_path
	resolved_path="$(resolve_flake_path "${flake_path}")"
	if [[ ! -d "${resolved_path}" ]]; then
		: "[ERROR] [${job_name}] Could not locate flake path: ${resolved_path}"
		return 1
	fi
	if [[ ! -f "${resolved_path}/flake.nix" ]]; then
		: "[ERROR] [${job_name}] Missing flake.nix at: ${resolved_path}/flake.nix"
		return 1
	fi

	# Create output directory
	mkdir -p "${output_dir}"

	# Build nix command with optional input overrides
	local -a nix_args=(
		"build"
		"--system" "aarch64-linux"
		"--extra-experimental-features" "nix-command"
		"--extra-experimental-features" "flakes"
		"--no-link"
	)

	if ! refresh_flake_lock_if_needed "${job_name}" "${resolved_path}" "${log_file}"; then
		return 1
	fi

	if should_only_update_locks; then
		: "[$(date)] [${job_name}] Lock-only mode enabled; skipping build for ${package_name}"
		return 0
	fi

	local build_target=".#${package_attr}"
	: "[$(date)] [${job_name}] Flake path: ${resolved_path}"

	if (
		cd "${resolved_path}"
		nix "${nix_args[@]}" "${build_target}"
	) 2>&1 | tee "${log_file}"; then
		: "[$(date)] [${job_name}] ✓ Built ${package_name}"
		return 0
	else
		: "[ERROR] [${job_name}] Failed to build ${package_name}"
		return 1
	fi
}

# Main build loop
declare -i total_jobs=0
declare -i failed_jobs=0

# Get number of jobs
num_jobs=$(yq eval '.jobs | length' "${BUILDS_DESCRIPTOR}" 2>/dev/null || true)
num_jobs=${num_jobs:-0}

if [[ $num_jobs -eq 0 ]]; then
	: "[WARNING] No builds defined in descriptor"
	exit 0
fi

: "[$(date)] Found ${num_jobs} build job(s)"

validate_worktree_mode
: "[$(date)] Shim builder worktree mode: ${WORKTREE_MODE}"
activate_flox_environment
rke2lab::env:load

# Check if yq is available
if ! command -v yq &>/dev/null; then
	: "[ERROR] yq not found. Please install yq to parse YAML descriptor"
	exit 1
fi

clear_job_vars() {
	unset name description enabled flakePath outputDir logFile
}

clear_package_vars() {
	unset name attr
}

for ((i = 0; i < num_jobs; i++)); do
	# Extract job configuration (excluding packages array)
	clear_job_vars
	source <(yq -o shell ".jobs[$i] | del(.packages)" "${BUILDS_DESCRIPTOR}")

	job_name="${name:-}"
	job_enabled="${enabled:-false}"
	job_description="${description:-}"
	flake_path="${flakePath:-}"
	output_dir="${outputDir:-}"
	log_file="$(resolve_log_file_path "${logFile:-}" "${job_name:-unnamed-job}")"

	# Skip disabled jobs
	if [[ "${job_enabled}" != "true" ]]; then
		: "[$(date)] [${job_name}] SKIPPED (disabled)"
		continue
	fi

	: ""
	: "╔═══════════════════════════════════════════════════════════════="
	: "║ Job: ${job_name}"
	: "║ Description: ${job_description}"
	: "║ Flake path: ${flake_path}"
	: "║ Output: ${output_dir}"
	: "╚═══════════════════════════════════════════════════════════════="

	((total_jobs += 1))

	# Get number of packages in this job
	num_packages=$(yq eval ".jobs[$i].packages | length" "${BUILDS_DESCRIPTOR}" 2>/dev/null || echo "0")
	: "[$(date)] [${job_name}] Packages: ${num_packages}"
	job_failed=false

	for ((j = 0; j < num_packages; j++)); do
		# Load individual package variables
		clear_package_vars
		source <(yq -o shell ".jobs[$i].packages[$j]" "${BUILDS_DESCRIPTOR}")

		package_name="${name:-}"
		package_attr="${attr:-}"

		readarray -t package_variant < <(resolve_package_variant "${package_name}" "${package_attr}")
		package_name="${package_variant[0]}"
		package_attr="${package_variant[1]}"

		if [[ -z "${name}" || -z "${attr}" ]]; then
			: "[WARN] [${job_name}] Missing package data at index ${j}; skipping"
			continue
		fi

		if ! build_package "${job_name}" "${flake_path}" "${output_dir}" "${log_file}" \
			"${package_name}" "${package_attr}"; then
			job_failed=true
		fi
	done

	if [[ "${job_failed}" == true ]]; then
		((failed_jobs += 1))
		: "[$(date)] [${job_name}] ✗ Job failed"
	else
		: "[$(date)] [${job_name}] ✓ All packages built successfully"
	fi
	: ""
done

: "Build Summary:"
: "  Total jobs: ${total_jobs}"
: "  Failed jobs: ${failed_jobs}"

if [[ $failed_jobs -gt 0 ]]; then
	: "[ERROR] ${failed_jobs} job(s) failed"
	exit 1
else
	: "[$(date)] ✓ All build jobs completed successfully"
	exit 0
fi
