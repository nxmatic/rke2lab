#!/bin/bash
set -exuo pipefail

# Flox shim package build script
# Builds packages from local packaged flakes defined in YAML descriptor
# Usage: flox-shim-build.sh <mode> [descriptor file]

source <(flox activate --dir /var/lib/rancher/rke2)

SCRIPT_PATH="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="${SCRIPT_PATH%/*}"
if [[ "${SCRIPT_DIR}" == "${SCRIPT_PATH}" ]]; then
	SCRIPT_DIR='.'
fi
SCRIPT_BASENAME="$(basename "${SCRIPT_PATH}")"
SCRIPT_STEM="${SCRIPT_BASENAME%.sh}"
WORKTREE_MODE="${FLOX_SHIM_WORKTREE_MODE:-}"

if [[ $# -gt 0 ]]; then
	WORKTREE_MODE="${1}"
	shift
fi

BUILDS_DESCRIPTOR="${1:-${SCRIPT_DIR}/${SCRIPT_STEM}.yaml}"
BUILDS_DESCRIPTOR_DIR="$(dirname "${BUILDS_DESCRIPTOR}")"

# Environment variables
RKE2LAB_ROOT="${RKE2LAB_ROOT:-/srv/host}"
GIT_WORKDIR="${GIT_WORKDIR:-${RKE2LAB_ROOT}/git}"

validate_worktree_mode() {
	case "${WORKTREE_MODE}" in
		rke2lab-worktree|flox-shim-worktree)
			return 0
			;;
		*)
			: "[ERROR] Unsupported or missing shim builder mode: '${WORKTREE_MODE}'"
			: "[ERROR] Usage: ${SCRIPT_BASENAME} <rke2lab-worktree|flox-shim-worktree> [descriptor file]"
			: "[ERROR] You can also set FLOX_SHIM_WORKTREE_MODE and pass only [descriptor file]."
			exit 1
			;;
	esac
}

: "[$(date)] Starting flox shim package builds from descriptor: ${BUILDS_DESCRIPTOR}"

# Check if descriptor exists
if [[ ! -f "${BUILDS_DESCRIPTOR}" ]]; then
	: "[ERROR] Builds descriptor not found: ${BUILDS_DESCRIPTOR}"
	exit 1
fi

# Check if yq is available
if ! command -v yq &>/dev/null; then
	: "[ERROR] yq not found. Please install yq to parse YAML descriptor"
	exit 1
fi

# Resolve local flake path from descriptor directory when relative
resolve_flake_path() {
	local flake_path="$1"

	if [[ "${flake_path}" == /* ]]; then
		echo "${flake_path}"
		return 0
	fi

	echo "${BUILDS_DESCRIPTOR_DIR}/${flake_path}"
}

resolve_kdns_src_worktree() {
	local -a candidates=()
	case "${WORKTREE_MODE}" in
		rke2lab-worktree)
			candidates+=(
				"${BUILDS_DESCRIPTOR_DIR}/networking/kdns/src"
				"${BUILDS_DESCRIPTOR_DIR}/networking/kdns"
			)
			;;
		flox-shim-worktree)
			candidates+=(
				"${BUILDS_DESCRIPTOR_DIR}/networking/kdns/src"
				"${BUILDS_DESCRIPTOR_DIR}/networking/kdns"
				"${GIT_WORKDIR}/lab42/kdns"
				"/srv/host/git/lab42/kdns"
				"/var/lib/git/lab42/kdns"
			)
			;;
	esac

	if [[ -n "${KDNS_SRC_WORKTREE:-}" ]]; then
		candidates=("${KDNS_SRC_WORKTREE}" "${candidates[@]}")
	fi

	local candidate
	for candidate in "${candidates[@]}"; do
		[[ -n "${candidate}" ]] || continue
		if [[ -d "${candidate}" && -f "${candidate}/flake.nix" ]]; then
			echo "${candidate}"
			return 0
		fi
		if [[ -d "${candidate}" && -f "${candidate}/go.mod" ]]; then
			echo "${candidate}"
			return 0
		fi
	done

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

	# Add worktree override for kdns-src input if building kdns
	if [[ "${package_name}" == "kdns" ]]; then
		local kdns_src_worktree
		if ! kdns_src_worktree="$(resolve_kdns_src_worktree)"; then
			: "[ERROR] [${job_name}] Unable to resolve kdns source worktree."
			: "[ERROR] [${job_name}] Expected one of:"
			: "[ERROR] [${job_name}]   - ${BUILDS_DESCRIPTOR_DIR}/networking/kdns/src (preferred subtree mode)"
			: "[ERROR] [${job_name}]   - ${BUILDS_DESCRIPTOR_DIR}/networking/kdns"
			: "[ERROR] [${job_name}]   - ${GIT_WORKDIR}/lab42/kdns"
			: "[ERROR] [${job_name}]   - /srv/host/git/lab42/kdns"
			: "[ERROR] [${job_name}]   - /var/lib/git/lab42/kdns"
			: "[ERROR] [${job_name}] Or set KDNS_SRC_WORKTREE explicitly."
			return 1
		fi

		nix_args+=(
			"--override-input" "kdns-src" "path:${kdns_src_worktree}"
		)
		: "[$(date)] [${job_name}] Using kdns source override (worktree mode: ${WORKTREE_MODE}): ${kdns_src_worktree}"
	fi

	local build_target=".#${package_attr}"
	: "[$(date)] [${job_name}] Flake path: ${resolved_path}"

	if (
		cd "${resolved_path}"
		nix "${nix_args[@]}" "${build_target}"
	) 2>&1 | tee -a "${log_file}"; then
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
	log_file="${logFile:-}"

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
