#!/usr/bin/env -S bash -exu -o pipefail

: "Load RKE2 environment for kubectl"
source <(flox activate --dir /var/lib/rancher/rke2)

MANIFEST_FILE="/srv/host/manifests/clusterapi/staged/image-state-configmap.yaml"

if [[ ! -f "${MANIFEST_FILE}" ]]; then
	echo "image-state manifest not found: ${MANIFEST_FILE}" >&2
	exit 1
fi

: "Ensure capn-system namespace exists"
kubectl get namespace capn-system >/dev/null 2>&1 ||
	kubectl create namespace capn-system

: "Apply the CDK8s-synthesized image-state ConfigMap"
kubectl apply -f "${MANIFEST_FILE}"

echo "image-state ConfigMap applied from ${MANIFEST_FILE}"
