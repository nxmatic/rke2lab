#!/usr/bin/env -S bash -exuo pipefail

set +x # Silence flox activation noise
source <(flox activate --dir /var/lib/rancher/rke2)
set -x

log() {
    echo "[rke2-openebs-ready] $*"
}

wait_for_storageclass() {
    local sc="${1:?storageclass name required}" timeout="${2:-300}" interval="${3:-5}"
    local start end
    start="$(date +%s)"
    end=$((start + timeout))
    while ! kubectl get storageclass "${sc}" >/dev/null 2>&1; do
        if (($(date +%s) >= end)); then
            log "StorageClass ${sc} not ready after ${timeout}s"
            kubectl get storageclass || true
            return 1
        fi
        log "Waiting for StorageClass ${sc}..."
        sleep "${interval}"
    done
    log "StorageClass ${sc} detected"
    kubectl get storageclass "${sc}"
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

if [[ -n "${RKE2LAB_MANIFESTS_PUBLISH_STORAGE_ENABLED:-}" ]] && ! bool_is_true "${RKE2LAB_MANIFESTS_PUBLISH_STORAGE_ENABLED}"; then
    log "Publishing disabled for storage layer; skipping OpenEBS readiness checks"
    exit 0
fi

log "Waiting for OpenEBS components..."

kubectl wait --for=create namespace/openebs --timeout=30s
kubectl wait --for=jsonpath='{.status.phase}'=Active namespace/openebs --timeout=10s
kubectl wait --for=condition=complete job/helm-install-openebs-zfs --namespace=openebs --timeout=300s
kubectl -n openebs rollout status deployment/openebs-zfs-zfs-localpv-controller --timeout=300s
kubectl -n openebs rollout status daemonset/openebs-zfs-zfs-localpv-node --timeout=300s
kubectl wait --for=condition=established crd/zfsvolumes.zfs.openebs.io crd/zfssnapshots.zfs.openebs.io crd/zfsnodes.zfs.openebs.io --namespace=openebs --timeout=300s
wait_for_storageclass openebs-zfs 300 5

log "OpenEBS ZFS is ready"
