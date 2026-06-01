#!/usr/bin/env -S bash -exu -o pipefail

: "Load RKE2 environment for kubectl and tooling"
source <(flox activate --dir /var/lib/rancher/rke2)

log() {
	echo "[rke2-layer-ready] $*"
}

usage() {
	echo "Usage: $(basename "$0") <layer|layer/subpath> [--package <name>] [--timeout <duration>]" >&2
	echo "Example: $(basename "$0") networking" >&2
	echo "         $(basename "$0") mesh" >&2
	echo "         $(basename "$0") storage --package openebs-zfs" >&2
	echo "         $(basename "$0") replication --timeout 600s" >&2
	echo "         $(basename "$0") runtime --timeout infinite" >&2
}

bool_is_true() {
	case "${1:-}" in
	1 | true | TRUE | yes | YES | on | ON)
		return 0
		;;
	*)
		return 1
		;;
	esac
}

policy_link_var_name() {
	local layer_key="${1:?layer key required}"
	printf 'RKE2LAB_POLICY_LINK_%s_ENABLED\n' "$(printf '%s' "${layer_key}" | tr '[:lower:]-/' '[:upper:]__')"
}

layer_is_policy_linkable() {
	case "${1:-}" in
	high-availability | networking | replication | storage | mesh)
		return 0
		;;
	*)
		return 1
		;;
	esac
}

layer_readiness_enabled() {
	local layer_key="${1:?layer key required}"
	local var_name value

	if ! layer_is_policy_linkable "${layer_key}"; then
		return 0
	fi

	var_name="$(policy_link_var_name "${layer_key}")"
	value="${!var_name:-}"
	if [[ -z "${value}" ]]; then
		log "Missing required policy variable for layer ${layer_key}: ${var_name}"
		return 1
	fi

	bool_is_true "${value}"
}

timeout_is_infinite() {
	[[ "${timeout}" == "infinite" ]]
}

log_command_output() {
	local output="${1:-}"

	[[ -n "${output}" ]] || return 0
	while IFS= read -r line; do
		[[ -n "${line}" ]] || continue
		log "${line}"
	done <<<"${output}"
}

resource_status_snapshot() {
	local namespace="${1:?namespace required}"
	local resource="${2:?resource required}"
	local name="${3:?resource name required}"
	local output

	log "Snapshot for ${resource}/${name} in namespace ${namespace}"
	output="$(kubectl -n "${namespace}" get "${resource}/${name}" -o wide 2>&1 || true)"
	log_command_output "${output}"

	log "Pods currently visible in namespace ${namespace}"
	output="$(kubectl -n "${namespace}" get pods -o wide 2>&1 || true)"
	log_command_output "${output}"

	log "Recent warning events in namespace ${namespace}"
	output="$({ kubectl -n "${namespace}" get events --sort-by=.lastTimestamp --field-selector type=Warning 2>&1 || true; } | tail -n 20)"
	log_command_output "${output}"
}

wait_for_crd_established() {
	local crd="${1:?crd name required}"
	local output=""
	local last_snapshot_epoch=0
	local now_epoch

	if ! timeout_is_infinite; then
		kubectl wait --for=condition=established "crd/${crd}" --timeout="${timeout}"
		return 0
	fi

	while true; do
		if output="$(kubectl wait --for=condition=established "crd/${crd}" --timeout=10s 2>&1)"; then
			log_command_output "${output}"
			return 0
		fi

		now_epoch="$(date +%s)"
		if ((now_epoch - last_snapshot_epoch >= 60)); then
			log "CRD ${crd} is not established yet; continuing to wait indefinitely"
			log_command_output "${output}"
			last_snapshot_epoch="${now_epoch}"
		fi

		sleep 10
	done
}

wait_for_namespace_ready() {
	local namespace="${1:?namespace required}"
	local output=""
	local last_snapshot_epoch=0
	local now_epoch

	if ! timeout_is_infinite; then
		kubectl wait --for=create "namespace/${namespace}" --timeout=30s
		kubectl wait --for=jsonpath='{.status.phase}'=Active "namespace/${namespace}" --timeout="10s"
		return 0
	fi

	while true; do
		if output="$(kubectl wait --for=create "namespace/${namespace}" --timeout=10s 2>&1)"; then
			log_command_output "${output}"
			break
		fi

		now_epoch="$(date +%s)"
		if ((now_epoch - last_snapshot_epoch >= 60)); then
			log "Namespace ${namespace} has not been created yet; continuing to wait indefinitely"
			log_command_output "${output}"
			last_snapshot_epoch="${now_epoch}"
		fi

		sleep 10
	done

	last_snapshot_epoch=0
	while true; do
		if output="$(kubectl wait --for=jsonpath='{.status.phase}'=Active "namespace/${namespace}" --timeout=10s 2>&1)"; then
			log_command_output "${output}"
			return 0
		fi

		now_epoch="$(date +%s)"
		if ((now_epoch - last_snapshot_epoch >= 60)); then
			log "Namespace ${namespace} is not Active yet; continuing to wait indefinitely"
			log_command_output "${output}"
			last_snapshot_epoch="${now_epoch}"
		fi

		sleep 10
	done
}

wait_for_resource_created() {
	local namespace="${1:?namespace required}"
	local resource="${2:?resource required}"
	local name="${3:?resource name required}"
	local output=""
	local last_snapshot_epoch=0
	local now_epoch

	if ! timeout_is_infinite; then
		kubectl -n "${namespace}" wait --for=create "${resource}/${name}" --timeout="${timeout}"
		return 0
	fi

	while true; do
		if output="$(kubectl -n "${namespace}" wait --for=create "${resource}/${name}" --timeout=10s 2>&1)"; then
			log_command_output "${output}"
			return 0
		fi

		now_epoch="$(date +%s)"
		if ((now_epoch - last_snapshot_epoch >= 60)); then
			log "${resource}/${name} has not been created yet in namespace ${namespace}; continuing to wait indefinitely"
			log_command_output "${output}"
			resource_status_snapshot "${namespace}" "${resource}" "${name}"
			last_snapshot_epoch="${now_epoch}"
		fi

		sleep 10
	done
}

wait_for_workload_rollout() {
	local namespace="${1:?namespace required}"
	local resource="${2:?resource required}"
	local name="${3:?resource name required}"
	local output=""
	local last_snapshot_epoch=0
	local now_epoch

	if ! timeout_is_infinite; then
		kubectl -n "${namespace}" rollout status "${resource}/${name}" --timeout="${timeout}"
		return 0
	fi

	while true; do
		if output="$(kubectl -n "${namespace}" rollout status "${resource}/${name}" --timeout=10s 2>&1)"; then
			log_command_output "${output}"
			return 0
		fi

		now_epoch="$(date +%s)"
		if ((now_epoch - last_snapshot_epoch >= 60)); then
			log "${resource}/${name} rollout is still pending in namespace ${namespace}; continuing to wait indefinitely"
			log_command_output "${output}"
			resource_status_snapshot "${namespace}" "${resource}" "${name}"
			last_snapshot_epoch="${now_epoch}"
		fi

		sleep 10
	done
}

layer=""
package_filter=""
timeout="${RKE2_LAYER_READY_TIMEOUT:-}"

while [[ $# -gt 0 ]]; do
	case "$1" in
	-p | --package)
		package_filter="${2:-}"
		if [[ -z "${package_filter}" ]]; then
			log "Missing package name for $1"
			usage
			exit 1
		fi
		shift 2
		;;
	-t | --timeout)
		timeout="${2:-}"
		if [[ -z "${timeout}" ]]; then
			log "Missing timeout value for $1"
			usage
			exit 1
		fi
		shift 2
		;;
	-h | --help)
		usage
		exit 0
		;;
	--)
		shift
		break
		;;
	-*)
		log "Unknown option: $1"
		usage
		exit 1
		;;
	*)
		if [[ -n "${layer}" ]]; then
			log "Unexpected argument: $1"
			usage
			exit 1
		fi
		layer="$1"
		shift
		;;
	esac
done

if [[ -z "${layer}" ]]; then
	usage
	exit 1
fi

layer="${layer%/}"

policy_layer_key="${layer%%/*}"
if ! layer_readiness_enabled "${policy_layer_key}"; then
	log "Policy disables layer ${policy_layer_key}; skipping readiness checks"
	exit 0
fi

if [[ -z "${timeout}" ]]; then
	case "${layer}" in
	runtime)
		timeout="infinite"
		;;
	*)
		timeout="300s"
		;;
	esac
fi

base_dir="${RKE2LAB_MANIFESTS_DIR:-/srv/host/rke2-manifests.d}"
src_dir="${base_dir}/${layer}"

if [[ ! -d "${src_dir}" ]]; then
	log "Manifest directory not found: ${src_dir}"
	exit 1
fi

if [[ -n "${package_filter}" ]]; then
	package_dir="${src_dir}/${package_filter}"
	if [[ -d "${package_dir}" ]]; then
		src_dir="${package_dir}"
	else
		log "Package directory not found: ${package_dir}; falling back to layer scan"
	fi
fi

declare -a namespaces=()
declare -a crds=()
declare -a workloads=()

loadMetadataFromManifestFiles() {
	local -a files
	local package_selector
	while IFS= read -r -d '' file; do
		files+=("$file")
	done < <(find "${src_dir}" -type f \( -name '*.yaml' -o -name '*.yml' \) -print0)

	if [[ ${#files[@]} -eq 0 ]]; then
		return 0
	fi

	namespaces=($(yq ea '[.. | select(has("namespace")) | .namespace | select(.)] | unique | .[]' "${files[@]}"))

	package_selector='.'
	if [[ -n "${package_filter}" ]]; then
		package_selector='select(.metadata.annotations["io.nxmatic.rke2lab/package"] == "'"${package_filter}"'")'
	fi

	while IFS= read -r line; do
		[[ -z "${line}" ]] && continue
		crds+=("${line}")
	done < <(yq -r "${package_selector} | select(.kind == \"CustomResourceDefinition\") | .metadata.name" "${files[@]}" | sort -u)

	while IFS= read -r line; do
		[[ -z "${line}" ]] && continue
		workloads+=("${line}")
	done < <(
		yq -r "${package_selector} | select(.kind == \"Deployment\" or .kind == \"DaemonSet\" or .kind == \"StatefulSet\") |
      [.kind, .metadata.name, (.metadata.namespace // \"default\")] | @tsv" "${files[@]}" | sort -u
	)
}

loadMetadataFromManifestFiles

if [[ ${#crds[@]} -gt 0 ]]; then
	log "Waiting for CRDs to be established"
	for crd in "${crds[@]}"; do
		wait_for_crd_established "${crd}"
	done
fi

log "Ensuring namespaces exist for layer ${layer}${package_filter:+ (package ${package_filter})}"
if [[ ${#namespaces[@]} -eq 0 ]]; then
	log "No namespaces found for layer ${layer}${package_filter:+ (package ${package_filter})}; skipping namespace creation"
	exit 0
fi
for namespace in "${namespaces[@]}"; do
	wait_for_namespace_ready "${namespace}"
done

log "Waiting for workloads in layer ${layer}${package_filter:+ (package ${package_filter})}"
for entry in "${workloads[@]}"; do
	IFS=$'\t' read -r kind name namespace <<<"${entry}"
	resource=$(echo "${kind}" | tr '[:upper:]' '[:lower:]')

	kubectl get namespace "${namespace}" >/dev/null 2>&1 || kubectl create namespace "${namespace}" >/dev/null

	# Wait for resource to be created by RKE2 from manifest directory
	log "Waiting for ${resource}/${name} to be created in namespace ${namespace}"
	wait_for_resource_created "${namespace}" "${resource}" "${name}"

	# Wait for rollout to complete
	log "Waiting for ${resource}/${name} rollout to complete"
	wait_for_workload_rollout "${namespace}" "${resource}" "${name}"
done

log "Layer ${layer} workloads are ready"
