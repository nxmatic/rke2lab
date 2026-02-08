#!/usr/bin/env -S bash -exuo pipefail

# RKE2 Generic Package Build Script
# Builds packages from multiple flakes based on YAML descriptor
# Usage: rke2lab-flox-build.sh [descriptor file]

source <( flox activate --dir /var/lib/rancher/rke2 )

BUILDS_DESCRIPTOR="${1:-/srv/host/flox-builds.yaml}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Required environment variables
RKE2LAB_ROOT="${RKE2LAB_ROOT:-/srv/host}"
RKE2LAB_CLUSTER_NAME="${RKE2LAB_CLUSTER_NAME:-}"
RKE2LAB_NODE_NAME="${RKE2LAB_NODE_NAME:-}"
GIT_WORKDIR="${RKE2LAB_ROOT}/git"

# Validate required environment variables
if [[ -z "${RKE2LAB_CLUSTER_NAME}" ]]; then
	: "[ERROR] RKE2LAB_CLUSTER_NAME environment variable is not set"
	exit 1
fi

if [[ -z "${RKE2LAB_NODE_NAME}" ]]; then
	: "[ERROR] RKE2LAB_NODE_NAME environment variable is not set"
	exit 1
fi

: "[$(date)] Starting RKE2 package builds from descriptor: ${BUILDS_DESCRIPTOR}"
: "[$(date)] Cluster: ${RKE2LAB_CLUSTER_NAME}, Node: ${RKE2LAB_NODE_NAME}"

# Helper function to resolve flake paths
# Relative paths are resolved relative to rke2.d/{cluster}/{node}/
resolve_flake_path() {
	local flake_path="$1"
	
	# If path is absolute and exists, use as-is
	if [[ "$flake_path" == /* ]]; then
		if [[ -d "$flake_path" ]]; then
			echo "$flake_path"
			return 0
		else
			# Absolute path doesn't exist - return it for error reporting
			echo "$flake_path"
			return 1
		fi
	fi
	
	# For relative paths, resolve within rke2.d/{cluster}/{node}/
	local base_path="${GIT_WORKDIR}/nxmatic/rke2lab/rke2.d/${RKE2LAB_CLUSTER_NAME}/${RKE2LAB_NODE_NAME}"
	local resolved_path="${base_path}/${flake_path}"
	
	if [[ -d "$resolved_path" ]]; then
		echo "$resolved_path"
		return 0
	fi
	
	# Return original path if nothing matched (for error reporting)
	echo "$flake_path"
	return 1
}

# Check if descriptor exists
if [[ ! -f "${BUILDS_DESCRIPTOR}" ]]; then
	: "[ERROR] Builds descriptor not found: ${BUILDS_DESCRIPTOR}"
	exit 1
fi

# Check if yq is available
if ! command -v yq &> /dev/null; then
	: "[ERROR] yq not found. Please install yq to parse YAML descriptor"
	exit 1
fi

# Function to build a single package
build_package() {
	local job_name="$1"
	local flake_path="$2"
	local output_dir="$3"
	local log_file="$4"
	local package_name="$5"
	local package_attr="$6"

	: "[$(date)] [${job_name}] Building ${package_name}..."

	# Resolve flake path (handles relative and absolute paths)
	local resolved_path
	resolved_path=$(resolve_flake_path "$flake_path")
	if [[ ! -d "$resolved_path" ]]; then
		: "[ERROR] [${job_name}] Could not locate flake at ${flake_path}"
		return 1
	fi
	flake_path="$resolved_path"

	# Create output directory
	mkdir -p "${output_dir}"

	# Verify flake.nix exists
	if [[ ! -f "${flake_path}/flake.nix" ]]; then
		: "[ERROR] [${job_name}] Flake not found at ${flake_path}/flake.nix"
		return 1
	fi

	# Build nix command with optional input overrides
	local -a nix_args=(
		"build"
		"--system" "aarch64-linux"
		"--extra-experimental-features" "nix-command"
		"--extra-experimental-features" "flakes"
		"--no-link"
	)

	# Add override for kdns-src input if building kdns
	# Handles the path difference: /var/lib/git vs /srv/host/git
	if [[ "${package_name}" == "kdns" ]]; then
		nix_args+=(
			"--override-input" "kdns-src" "git+file://${GIT_WORKDIR}/lab42/kdns"
		)
		: "[$(date)] [${job_name}] Using kdns source: ${GIT_WORKDIR}/lab42/kdns"
	fi

	nix_args+=( ".#${package_attr}" )

	# Build package
	if cd "${flake_path}" && \
	   nix "${nix_args[@]}" 2>&1 | tee -a "${log_file}"; then
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
	: "║ Flake: ${flake_path}"
	: "║ Output: ${output_dir}"
	: "╚═══════════════════════════════════════════════════════════════="

	((total_jobs+=1))

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
		((failed_jobs+=1))
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
