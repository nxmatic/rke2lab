#!/usr/bin/env -S bash -xu -o pipefail
source <(flox activate --dir /var/lib/rancher/rke2)

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

if [[ -n "${RKE2LAB_POLICY_LINK_NETWORKING_ENABLED:-}" ]] && ! bool_is_true "${RKE2LAB_POLICY_LINK_NETWORKING_ENABLED}"; then
	echo "[rke2-cilium-ready] policy disables networking layer; skipping cilium readiness checks"
	exit 0
fi

kubectl wait --for=condition=Ready nodes --all --timeout=300s || true
kubectl wait --for=condition=Ready pods -l k8s-app=cilium -n kube-system --timeout=300s || true
kubectl wait --for=condition=Available deployment/cilium-operator -n kube-system --timeout=300s || true
cilium status --wait --wait-duration=300s || true
kubectl get ciliumloadbalancerippool -o wide || true
kubectl get svc control-plane-lb -n kube-system -o wide || true
kubectl get endpoints control-plane-lb -n kube-system -o wide || true
kubectl get ciliumBGPClusterConfig -o wide || true
kubectl get ciliumBGPAdvertisement -o wide || true
kubectl get ciliumL2AnnouncementPolicy -o wide || true
