#!/usr/bin/env bash

set -euxo pipefail

# Install RKE2 config fragments from ${RKE2LAB_CONFIG_DIR}
# into /etc/rancher/rke2/config.yaml.d before rke2-server starts.

source <( flox activate --dir /var/lib/cloud )

RKE2LAB_CONFIG_DIR=${RKE2LAB_CONFIG_DIR:?RKE2LAB_CONFIG_DIR is required}

if [[ ! -d "${RKE2LAB_CONFIG_DIR}" ]]; then
  echo "[rke2-config-install] source directory missing, skipping: ${RKE2LAB_CONFIG_DIR}" >&2
  exit 0
fi

DEST_DIR="/etc/rancher/rke2/config.yaml.d"

mkdir -p "${DEST_DIR}"

# find "${DEST_DIR}" -maxdepth 1 -type f -name '*.yaml' -delete

shopt -s nullglob
for cm in "${RKE2LAB_CONFIG_DIR}"/*.yaml; do
  if yq -e 'has("data") and (.data | type == "object")' "$cm" >/dev/null 2>&1; then
    yq -r '.data' "$cm" > "${DEST_DIR}/$(basename "$cm")"
  else
    cp "$cm" "${DEST_DIR}/$(basename "$cm")"
  fi
done

exit 0
