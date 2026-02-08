#!/usr/bin/env -S bash -exuo pipefail

# RKE2 Generic Package Build Script
# Builds packages from multiple flakes based on YAML descriptor
# Usage: rke2-nix-build.sh [descriptor file]

BUILDS_DESCRIPTOR="${1:-/srv/host/nix-builds.yaml}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "[$(date)] Starting RKE2 package builds from descriptor: ${BUILDS_DESCRIPTOR}"

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

	# Create output directory
	mkdir -p "${output_dir}"

	# Verify flake.nix exists
	if [[ ! -f "${flake_path}/flake.nix" ]]; then
		: "[ERROR] [${job_name}] Flake not found at ${flake_path}/flake.nix"
		return 1
	fi

	# Build package
	if cd "${flake_path}" && \
	   nix build \
		   --system aarch64-linux \
		   --out-link "${output_dir}/${package_name}" \
		   ".#${package_attr}" \
		   2>&1 | tee -a "${log_file}"; then
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
num_jobs=$(yq eval '.jobs | length' "${BUILDS_DESCRIPTOR}")

if [[ $num_jobs -eq 0 ]]; then
	: "[WARNING] No builds defined in descriptor"
	exit 0
fi

: "[$(date)] Found ${num_jobs} build job(s)"

for ((i = 0; i < num_jobs; i++)); do
	# Extract job configuration
	job_name=$(yq eval ".jobs[$i].name" "${BUILDS_DESCRIPTOR}")
	job_enabled=$(yq eval ".jobs[$i].enabled" "${BUILDS_DESCRIPTOR}")
	job_description=$(yq eval ".jobs[$i].description" "${BUILDS_DESCRIPTOR}")
	flake_path=$(yq eval ".jobs[$i].flakePath" "${BUILDS_DESCRIPTOR}")
	output_dir=$(yq eval ".jobs[$i].outputDir" "${BUILDS_DESCRIPTOR}")
	log_file=$(yq eval ".jobs[$i].logFile" "${BUILDS_DESCRIPTOR}")

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

	((total_jobs++))

	# Get number of packages in this job
	num_packages=$(yq eval ".jobs[$i].packages | length" "${BUILDS_DESCRIPTOR}")
	job_failed=false

	for ((j = 0; j < num_packages; j++)); do
		package_name=$(yq eval ".jobs[$i].packages[$j].name" "${BUILDS_DESCRIPTOR}")
		package_attr=$(yq eval ".jobs[$i].packages[$j].attr" "${BUILDS_DESCRIPTOR}")

		if ! build_package "${job_name}" "${flake_path}" "${output_dir}" "${log_file}" \
						   "${package_name}" "${package_attr}"; then
			job_failed=true
		fi
	done

	if [[ "${job_failed}" == true ]]; then
		((failed_jobs++))
		: "[$(date)] [${job_name}] ✗ Job failed"
	else
		: "[$(date)] [${job_name}] ✓ All packages built successfully"
		: "[$(date)] [${job_name}] Output: ${output_dir}"
		ls -lh "${output_dir}" 2>/dev/null | head -20 | tee -a "${log_file}"
	fi
	: ""
done

: "════════════════════════════════════════════════════════════════"
: "Build Summary:"
: "  Total jobs: ${total_jobs}"
: "  Failed jobs: ${failed_jobs}"
: "════════════════════════════════════════════════════════════════"

if [[ $failed_jobs -gt 0 ]]; then
	: "[ERROR] ${failed_jobs} job(s) failed"
	exit 1
else
	: "[$(date)] ✓ All build jobs completed successfully"
	exit 0
fi
