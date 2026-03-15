#!/usr/bin/env -S bash -exu -o pipefail

: "Load RKE2 environment for kubectl and tooling"
source <(flox activate --dir /var/lib/rancher/rke2)

is_capn_provider_initialized() {
  kubectl get crd lxcclusters.infrastructure.cluster.x-k8s.io >/dev/null 2>&1
}

ensure_incus_provider_available() {
  if clusterctl config repositories | grep -Eq '^incus[[:space:]]+InfrastructureProvider[[:space:]]+'; then
    : "clusterctl repositories already include incus infrastructure provider"
    return
  fi

  : "clusterctl repositories do not include incus provider; configure ~/.cluster-api/clusterctl.yaml first"
  exit 1
}

main() {
  : "Prefer GitHub CLI auth context when available"
  if [[ -z "${GITHUB_TOKEN:-}" ]] && command -v gh >/dev/null 2>&1; then
    if gh auth status >/dev/null 2>&1; then
      export GITHUB_TOKEN="$(gh auth token 2>/dev/null || true)"
    fi
  fi

  : "Reuse GitHub token to avoid clusterctl rate-limit issues when available"
  if [[ -z "${GITHUB_TOKEN:-}" && -n "${GITHUB_PAT:-}" ]]; then
    export GITHUB_TOKEN="${GITHUB_PAT}"
  fi

  : "Use in-cluster management kubeconfig if present"
  export KUBECONFIG="${KUBECONFIG:-/etc/rancher/rke2/rke2.yaml}"

  if ! command -v clusterctl >/dev/null 2>&1; then
    : "clusterctl not found; run rke2lab-cluster-api-install.service first"
    exit 1
  fi

  ensure_incus_provider_available

  if is_capn_provider_initialized; then
    : "CAPN provider already initialized (CRD lxcclusters.infrastructure.cluster.x-k8s.io exists); skipping"
    return 0
  fi

  local init_args
  init_args="${RKE2LAB_CLUSTERCTL_INCUS_INIT_ARGS:--i incus}"

  : "initializing CAPN provider with args: ${init_args}"
  # shellcheck disable=SC2206
  local args=( ${init_args} )
  clusterctl init "${args[@]}"
}

main "$@"
