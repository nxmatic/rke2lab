#!/usr/bin/env -S bash -exuo pipefail

set +x # Silence flox activation noise
source <(flox activate --dir /var/lib/rancher/rke2)
set -x

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

if [[ -n "${RKE2LAB_POLICY_LINK_REPLICATION_ENABLED:-}" ]] && ! bool_is_true "${RKE2LAB_POLICY_LINK_REPLICATION_ENABLED}"; then
	echo "[rke2-replicator-ready] policy disables replication layer; skipping replicator readiness checks"
	exit 0
fi

: "Waiting for kubernetes-replicator components..."

# Wait for kube-system namespace (should already exist but ensure it)
kubectl wait --for=create namespace/kube-system --timeout=30s
kubectl wait --for=jsonpath='{.status.phase}'=Active namespace/kube-system --timeout=10s

# Wait for the replicator deployment to be created and rolled out
: "Waiting for kubernetes-replicator deployment..."
kubectl -n kube-system wait --for=create deployment/kubernetes-replicator --timeout=120s
kubectl -n kube-system rollout status deployment/kubernetes-replicator --timeout=300s

# Ensure pods are actually running and ready
: "Waiting for kubernetes-replicator pods to be ready..."
kubectl wait --for=condition=Ready pods -l app.kubernetes.io/name=kubernetes-replicator -n kube-system --timeout=120s

# Verify the replicator is actually functional by checking its webhook/controller logs
# The replicator should log that it's watching resources
: "Verifying replicator is watching resources..."
if kubectl -n kube-system logs -l app.kubernetes.io/name=kubernetes-replicator --tail=50 2>/dev/null | grep -qiE '(started|watching|controller)'; then
	: "Replicator controller appears to be active"
else
	: "WARNING: Could not verify replicator logs - proceeding anyway"
fi

# Check cluster role and bindings are in place
: "Verifying RBAC for replicator..."
kubectl get clusterrole kubernetes-replicator >/dev/null
kubectl get clusterrolebinding kubernetes-replicator >/dev/null

: "kubernetes-replicator is ready"
: "Secrets and ConfigMaps with replication annotations will now be replicated across namespaces"
