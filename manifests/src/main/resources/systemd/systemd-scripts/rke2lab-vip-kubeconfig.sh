#!/usr/bin/env -S bash -exu -o pipefail

set +x # Silence flox activation noise
source <(flox activate --dir /var/lib/rancher/rke2)
set -x

: "Create working copy of kubeconfig"
: "Ensure kubeconfig dir is provided"
: "${RKE2LAB_KUBECONFIG_DIR:?RKE2LAB_KUBECONFIG_DIR is required}"
# The kubeconfig dir is the cluster-scoped mount source (bind-mounted into every node in the
# cluster). The filename is fixed (the cluster name is already in the parent path on the operator
# side).
KUBECONFIG="${RKE2LAB_KUBECONFIG_DIR}/kubeconfig.yaml"

mkdir -p $(dirname "$KUBECONFIG")
cp /etc/rancher/rke2/rke2.yaml "$KUBECONFIG"
chmod 644 "$KUBECONFIG"

: "Apply modifications to working copy"
yq --inplace --from-file=<(
	cat <<EoE
.clusters[0].cluster.name = "${RKE2LAB_CLUSTER_NAME}" |
.clusters[0].cluster.server = "https://${RKE2LAB_NETWORK_LAN_HOST_INETADDR}:6443" |
.clusters[0].name = "${RKE2LAB_CLUSTER_NAME}" |
.contexts[0].context.cluster = "${RKE2LAB_CLUSTER_NAME}" |
.contexts[0].context.namespace = "kube-system" |
.contexts[0].context.user = "${RKE2LAB_CLUSTER_NAME}" |
.contexts[0].name = "${RKE2LAB_CLUSTER_NAME}" |
.users[0].name = "${RKE2LAB_CLUSTER_NAME}" |
.current-context = "${RKE2LAB_CLUSTER_NAME}"
EoE
) "$KUBECONFIG"
