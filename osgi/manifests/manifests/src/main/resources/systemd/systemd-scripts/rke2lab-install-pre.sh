#!/usr/bin/env -S bash -exu -o pipefail

: "Load RKE2 environment (Nix + Flox + nocloud env are pre-provisioned by rke2lab-bootstrap-env.service)" # @codebase
RKE2LAB_ROOT=${RKE2LAB_ROOT:-/srv/host}
source "$(dirname "${BASH_SOURCE[0]}")/rke2lab-env-load.sh"

: "Set flox target system for this host"
RKE2_FLOX_SYSTEM="$(uname -m)-linux"
export RKE2_FLOX_SYSTEM

: "Activate the nocloud environment provisioned by rke2lab-bootstrap-env"
[[ -d /var/lib/cloud/.flox ]] || {
	echo "[rke2lab-install-pre] ERROR: /var/lib/cloud/.flox missing; rke2lab-bootstrap-env.service must run first" >&2
	exit 1
}
flox install --dir=/var/lib/cloud git gh@^2.86
set +x # Silence flox activation noise
source <(flox activate --dir=/var/lib/cloud)
set -x

: "Load GitHub credentials from worktree secrets (hard-fail on missing keys)"
rke2lab::secrets:load() {
	local secrets_file var key val
	secrets_file="${RKE2LAB_ROOT:-/srv/host}/rke2lab-worktree.d/.secrets"

	[[ -s "${secrets_file}" ]] || {
		echo "[rke2lab-install-pre] ERROR: secrets file missing or empty: ${secrets_file}" >&2
		exit 1
	}

	for pair in "GITHUB_USERNAME=.github.username" "GITHUB_PAT=.github.token"; do
		var="${pair%%=*}"
		key="${pair#*=}"
		val="$(yq -r "${key}" "${secrets_file}")"
		[[ -n "${val}" && "${val}" != "null" ]] || {
			echo "[rke2lab-install-pre] ERROR: ${key} missing in ${secrets_file}" >&2
			exit 1
		}
		export "${var}=${val}"
	done
}
rke2lab::secrets:load

: "GitHub authentication setup"
gh auth login --with-token <<EoF
${GITHUB_PAT}
EoF
gh auth setup-git --hostname "${GITHUB_HOST:-github.com}"

: "Configure ghcr registry access for containerd" # @codebase
CONTAINERD_REG_FILE="/etc/rancher/rke2/registries.yaml"
if [[ ! -f "${CONTAINERD_REG_FILE}" ]]; then
	: "[rke2-install-pre] registries.yaml not present; creating"
	mkdir -p "$(dirname "${CONTAINERD_REG_FILE}")"
	cat >"${CONTAINERD_REG_FILE}" <<EoF | cut -c 3-
  mirrors:
    ghcr.io:
      endpoint:
        - https://ghcr.io
  configs:
    "ghcr.io":
      auth:
        username: ${GITHUB_USERNAME}
        password: ${GITHUB_PAT}
EoF
	chmod 0644 "${CONTAINERD_REG_FILE}"
fi

: "Initialize the flox environment for RKE2"
[[ ! -d /var/lib/rancher/rke2/.flox ]] &&
	flox init --dir=/var/lib/rancher/rke2

: "Include cloud environment in RKE2 flox environment and configure groups"
dasel -i toml -o yaml \
	</var/lib/rancher/rke2/.flox/env/manifest.toml |
	yq eval '.options = {"systems": [env(RKE2_FLOX_SYSTEM)]}' - |
	yq eval '.include = {"environments": [{"dir": "/var/lib/cloud"}]}' - |
	# linux
	yq eval '.install += {"zfs": {"pkg-path": "zfs", "pkg-group": "host-linux"}}' - |
	# system
	yq eval '.install += {"direnv": {"pkg-path": "direnv", "pkg-group": "host-system"}}' - |
	yq eval '.install += {"gnutar": {"pkg-path": "gnutar", "pkg-group": "host-system"}}' - |
	yq eval '.install += {"xstow": {"pkg-path": "xstow", "pkg-group": "host-system"}}' - |
	# k8s
	yq eval '.install += {"etcdctl": {"pkg-path": "etcdctl", "pkg-group": "k8s-etcd"}}' - |
	yq eval '.install += {"ceph-client": {"pkg-path": "ceph-client", "pkg-group": "k8s-clients"}}' - |
	yq eval '.install += {"cilium-cli": {"pkg-path": "cilium-cli", "pkg-group": "k8s-clients"}}' - |
	yq eval '.install += {"helmfile": {"pkg-path": "helmfile", "pkg-group": "k8s-clients"}}' - |
	yq eval '.install += {"kubernetes-helm": {"pkg-path": "kubernetes-helm", "pkg-group": "k8s-clients"}}' - |
	yq eval '.install += {"nerdctl": {"pkg-path": "nerdctl", "pkg-group": "k8s-clients"}}' - |
	yq eval '.install += {"tektoncd-cli": {"pkg-path": "tektoncd-cli", "pkg-group": "k8s-clients"}}' - |
	# Flox currently exposes only a prerelease kpt v1 (1.0.0-beta.55), and its
	# semver range resolver does not match that prerelease through a generic v1 range.
	# Leave kpt unpinned until the catalog publishes a stable 1.x we can target.
	yq eval '.install += {"kpt": {"pkg-path": "kpt", "pkg-group": "k8s-config"}}' - |
	yq eval '.install += {"krew": {"pkg-path": "krew", "pkg-group": "k8s-clients"}}' - |
	yq eval '.install += {"kubectl": {"pkg-path": "kubectl", "pkg-group": "k8s-clients"}}' - |
	yq eval '.install += {"kubectl-ai": {"pkg-path": "kubectl-ai", "pkg-group": "k8s-plugins"}}' - |
	yq eval '.install += {"kubectl-ktop": {"pkg-path": "kubectl-ktop", "pkg-group": "k8s-plugins"}}' - |
	yq eval '.install += {"kubectl-neat": {"pkg-path": "kubectl-neat", "pkg-group": "k8s-plugins"}}' - |
	yq eval '.install += {"kubectl-tree": {"pkg-path": "kubectl-tree", "pkg-group": "k8s-plugins"}}' - |
	yq eval '.install += {"kubectl-graph": {"pkg-path": "kubectl-graph", "pkg-group": "k8s-plugins"}}' - |
	yq eval '.install += {"kubectl-doctor": {"pkg-path": "kubectl-doctor", "pkg-group": "k8s-plugins"}}' - |
	yq eval '.install += {"kubectl-explore": {"pkg-path": "kubectl-explore", "pkg-group": "k8s-plugins"}}' - |
	yq eval '.install += {"kubectl-rook-ceph": {"pkg-path": "kubectl-rook-ceph", "pkg-group": "k8s-plugins"}}' - |
	yq eval '.install += {"kubectl-view-secret": {"pkg-path": "kubectl-view-secret", "pkg-group": "k8s-plugins"}}' - |
	yq eval '.install += {"tubekit": {"pkg-path": "tubekit", "pkg-group": "k8s-clients"}}' - |
	# runtime-java
	yq eval '.install += {"jdk25": {"pkg-path": "jdk25", "pkg-group": "runtime-java"}}' - |
	# manifests
	yq eval '.install += {"dasel": {"pkg-path": "dasel", "pkg-group": "manifest-yaml"}}' - |
	yq eval '.install += {"yq-go": {"pkg-path": "yq-go", "pkg-group": "manifest-yaml"}}' - |
	# cache
	yq eval '.install += {"cachix": {"pkg-path": "cachix", "pkg-group": "nix-cache"}}' - |
	# user
	yq eval '.install += {"delta": {"pkg-path": "delta", "pkg-group": "user-tools"}}' - |
	yq eval '.install += {"emacs-nox": {"pkg-path": "emacs-nox", "pkg-group": "user-tools"}}' - |
	yq eval '.profile = {"common": "source /var/lib/rancher/rke2/.flox/env/profile-common.sh"}' - |
	dasel -i yaml -o toml | tee /tmp/manifest.toml.$$ &&
	mv /tmp/manifest.toml.$$ \
		/var/lib/rancher/rke2/.flox/env/manifest.toml
cat <<'EoFloxCommonProfile' | cut -c 3- | tee /var/lib/rancher/rke2/.flox/env/profile-common.sh
  : "Load nocloud environment from the common profile"
  source "/var/lib/cloud/.flox/env/profile-common.sh"

   : "Create kubectl symlinks for the tekton cli"
  ln -sf "$(command -v tkn)" /usr/local/bin/kubectl-tkn || true

  set -a
  : "Load RKE2-specific dynamic environment variables"
  ARCH="$(dpkg --print-architecture)"
  [[ -r /etc/rancher/rke2/rke2.yaml ]] &&
    KUBECONFIG="/etc/rancher/rke2/rke2.yaml"

  : "Default cache for kubectl/kpt"
  KUBECACHEDIR="${KUBECACHEDIR:-/var/cache/rke2/kube-cache}"
  mkdir -p "${KUBECACHEDIR}"

  : "Set KREW_ROOT if not already set"
  KREW_ROOT="${KREW_ROOT:-/var/lib/rancher/rke2/krew}"

  : "Update PATH with RKE2 tools"
  PATH="/var/lib/rancher/rke2/bin:$PATH:${KREW_ROOT}/bin"
  set +a
EoFloxCommonProfile

: "Load the RKE2 envrc"
set +x # Silence flox activation noise
source <(flox activate --dir="/var/lib/rancher/rke2")
set -x

: "Initialize krew and install plugins"
KREW_ROOT="/var/lib/rancher/rke2/krew"
mkdir -p "$KREW_ROOT"

: "Install krew plugins using krew directly"
for plugin in ctx ns; do
	krew install "$plugin" || true
done
