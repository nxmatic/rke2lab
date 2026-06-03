#!/usr/bin/env bash

set -euxo pipefail

set +x # Silence flox activation noise
source <(flox activate -v -v -v --dir /var/lib/cloud)
set -x

# Install RKE2 config fragments from ${RKE2LAB_CONFIG_DIR}
# into /etc/rancher/rke2/config.yaml.d before rke2-server starts.

RKE2LAB_CONFIG_DIR=${RKE2LAB_CONFIG_DIR:?RKE2LAB_CONFIG_DIR is required}

if [[ ! -d "${RKE2LAB_CONFIG_DIR}" ]]; then
	echo "[rke2-config-install] source directory missing, skipping: ${RKE2LAB_CONFIG_DIR}" >&2
	exit 0
fi

DEST_DIR="/etc/rancher/rke2/config.yaml.d"

mkdir -p "${DEST_DIR}"

shopt -s nullglob
fragments=("${RKE2LAB_CONFIG_DIR}"/*.yaml "${RKE2LAB_CONFIG_DIR}"/*.yml)
shopt -u nullglob

if [[ ${#fragments[@]} -eq 0 ]]; then
	echo "[rke2-config-install] no config fragments found in: ${RKE2LAB_CONFIG_DIR}" >&2
	exit 0
fi

# Canonical path: config manifests in ${RKE2LAB_CONFIG_DIR} are ConfigMap resources.
# Render only .data payload into RKE2 config fragments.
find "${DEST_DIR}" -maxdepth 1 \( -type f -o -type l \) \( -name '*.yaml' -o -name '*.yml' \) -delete

for manifest in "${fragments[@]}"; do
	[[ -f "${manifest}" ]] || continue

	kind="$(yq eval -r '.kind // ""' "${manifest}" 2>/dev/null || true)"
	if [[ "${kind}" != "ConfigMap" ]]; then
		echo "[rke2-config-install] expected ConfigMap manifest, got kind='${kind}' for: ${manifest}" >&2
		exit 1
	fi

	fragment_name="$(yq eval -r '.metadata.name // ""' "${manifest}" 2>/dev/null || true)"
	if [[ -z "${fragment_name}" ]] || [[ "${fragment_name}" == "null" ]]; then
		echo "[rke2-config-install] missing metadata.name in ConfigMap manifest: ${manifest}" >&2
		exit 1
	fi

	yq eval -o=yaml '(.data // {}) | with_entries(.value |= from_yaml)' "${manifest}" >"${DEST_DIR}/${fragment_name}"
done

exit 0
