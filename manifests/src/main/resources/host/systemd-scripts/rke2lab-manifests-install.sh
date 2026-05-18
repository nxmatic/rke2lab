#!/usr/bin/env -S bash -exu -o pipefail

: "Load RKE2 flox environment for kubectl and tooling"
source <(flox activate --dir /var/lib/rancher/rke2)

RKE2LAB_ROOT=${RKE2LAB_ROOT:-/srv/host}
if [[ -r "${RKE2LAB_ROOT}/systemd-scripts.d/rke2lab-env-load.sh" ]]; then
	source "${RKE2LAB_ROOT}/systemd-scripts.d/rke2lab-env-load.sh"
	rke2lab::env:load
fi

if [[ -z "${RKE2LAB_MANIFESTS_DIR:-}" ]]; then
	echo "[rke2-manifests-install] RKE2LAB_MANIFESTS_DIR is required (exported by incus env file)" >&2
	exit 1
fi

if [[ -z "${RKE2_SERVER_MANIFESTS_DIR:-}" ]]; then
	echo "[rke2-manifests-install] RKE2_SERVER_MANIFESTS_DIR is required (exported by incus env file)" >&2
	exit 1
fi

BASE_DIR="${RKE2LAB_MANIFESTS_DIR}"
DST_DIR="${RKE2_SERVER_MANIFESTS_DIR}"

usage() {
	echo "Usage: $(basename "$0") <layer|layer/subpath>" >&2
	echo "Example: $(basename "$0") networking" >&2
	echo "         $(basename "$0") cicd/tekton-pipelines" >&2
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

layer_install_enabled() {
	local layer_key="${1:?layer key required}"
	local var_name value

	if ! layer_is_policy_linkable "${layer_key}"; then
		return 0
	fi

	var_name="$(policy_link_var_name "${layer_key}")"
	value="${!var_name:-}"
	if [[ -z "${value}" ]]; then
		echo "[rke2-manifests-install] missing required policy variable for layer ${layer_key}: ${var_name}" >&2
		return 1
	fi

	bool_is_true "${value}"
}

manifest_is_local_config() {
	local manifest=${1:?manifest path required}
	grep -Eq "^[[:space:]]*config\\.kubernetes\\.io/local-config:[[:space:]]*['\"]?true['\"]?[[:space:]]*$" "${manifest}"
}

link_manifest_tree() {
	local src_root=${1:?source root required}
	local target_root=${2:?target root required}
	local manifest rel_path target_path linked_count

	rm -rf "${target_root}"
	mkdir -p "${target_root}"
	linked_count=0

	while IFS= read -r -d '' manifest; do
		if manifest_is_local_config "${manifest}"; then
			continue
		fi

		rel_path="${manifest#"${src_root}/"}"
		target_path="${target_root}/${rel_path}"
		mkdir -p "$(dirname "${target_path}")"
		ln -sfn "${manifest}" "${target_path}"
		linked_count=$((linked_count + 1))
	done < <(find "${src_root}" -type f \( -name '*.yml' -o -name '*.yaml' \) -print0)

	if [[ "${linked_count}" -eq 0 ]]; then
		echo "[rke2-manifests-install] no cluster-applied manifests selected from: ${src_root}" >&2
	fi
}

if [[ $# -ne 1 ]]; then
	usage
	exit 1
fi

path="${1%/}"
layer_dir=$(dirname "${path}")
pkg_name=$(basename "${path}")

# Normalize layer_dir when no slash was provided
if [[ "${layer_dir}" == "." ]]; then
	layer_dir="${pkg_name}"
	pkg_name=""
fi

src_dir="${BASE_DIR}/${path}"
if [[ ! -d "${src_dir}" ]]; then
	echo "[rke2-manifests-install] source manifest directory not found: ${src_dir}" >&2
	exit 1
fi

if [[ -z "${pkg_name}" ]]; then
	: "Install all manifests for layer ${layer_dir}"
	target_dir="${DST_DIR}/${layer_dir}"
	policy_layer_key="${layer_dir}"
	if ! layer_install_enabled "${policy_layer_key}"; then
		echo "[rke2-manifests-install] policy disables layer ${policy_layer_key}; removing ${target_dir}" >&2
		rm -rf "${target_dir}"
		exit 0
	fi
	link_manifest_tree "${src_dir}" "${target_dir}"
else
	: "Install package ${pkg_name} for layer ${layer_dir}"
	target_dir="${DST_DIR}/${layer_dir}/${pkg_name}"
	policy_layer_key="${layer_dir}"
	if ! layer_install_enabled "${policy_layer_key}"; then
		echo "[rke2-manifests-install] policy disables layer ${policy_layer_key}; removing ${target_dir}" >&2
		rm -rf "${target_dir}"
		exit 0
	fi
	link_manifest_tree "${src_dir}" "${target_dir}"
fi
