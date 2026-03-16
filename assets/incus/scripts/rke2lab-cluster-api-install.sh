#!/usr/bin/env -S bash -exu -o pipefail

: "Load RKE2 environment for kubectl and tooling"
source <(flox activate --dir /var/lib/rancher/rke2)

arch() {
	case "$(uname -m)" in
	x86_64) echo "amd64" ;;
	aarch64 | arm64) echo "arm64" ;;
	ppc64le) echo "ppc64le" ;;
	*)
		: "unsupported architecture: $(uname -m)"
		exit 1
		;;
	esac
}

clusterctl_installed_version() {
	clusterctl version -o short 2>/dev/null | awk 'NF { print $1; exit }'
}

install_clusterctl() {
	local version download_url tmp_bin
	version="$1"
	download_url="https://github.com/kubernetes-sigs/cluster-api/releases/download/${version}/clusterctl-linux-$(arch)"
	tmp_bin="$(mktemp)"

	: "downloading clusterctl ${version} from ${download_url}"
	curl -fsSL "${download_url}" -o "${tmp_bin}"
	install -m 0755 "${tmp_bin}" /usr/local/bin/clusterctl
	rm -f "${tmp_bin}"

	: "installed clusterctl: $(clusterctl version -o short || true)"
}

ensure_clusterctl() {
	local desired_version upgrade_policy installed_version
	desired_version="${RKE2LAB_CLUSTERCTL_VERSION:-v1.12.3}"
	upgrade_policy="${RKE2LAB_CLUSTERCTL_UPGRADE_POLICY:-if-missing}"

	if ! command -v clusterctl >/dev/null 2>&1; then
		install_clusterctl "${desired_version}"
		return
	fi

	installed_version="$(clusterctl_installed_version || true)"
	: "clusterctl already installed: ${installed_version:-unknown}"

	case "${upgrade_policy}" in
	if-missing)
		: "clusterctl upgrade policy is if-missing; keeping existing binary"
		;;
	if-mismatch)
		if [[ -n "${installed_version}" && "${installed_version}" != "${desired_version}" ]]; then
			: "clusterctl version mismatch (${installed_version} != ${desired_version}); upgrading"
			install_clusterctl "${desired_version}"
		fi
		;;
	always)
		: "clusterctl upgrade policy is always; reinstalling ${desired_version}"
		install_clusterctl "${desired_version}"
		;;
	*)
		: "unsupported RKE2LAB_CLUSTERCTL_UPGRADE_POLICY=${upgrade_policy}; expected if-missing|if-mismatch|always"
		exit 1
		;;
	esac
}

is_cluster_api_initialized() {
	kubectl get crd clusters.cluster.x-k8s.io >/dev/null 2>&1
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

	ensure_clusterctl

	if is_cluster_api_initialized; then
		: "Cluster API already initialized (CRD clusters.cluster.x-k8s.io exists); skipping"
		return 0
	fi

	local init_args
	init_args="${RKE2LAB_CLUSTERCTL_INIT_ARGS:-}"

	if [[ -n "${init_args}" ]]; then
		: "initializing management cluster with custom args: ${init_args}"
		# shellcheck disable=SC2206
		local args=(${init_args})
		clusterctl init "${args[@]}"
	else
		: "initializing management cluster with core defaults"
		clusterctl init
	fi
}

main "$@"
