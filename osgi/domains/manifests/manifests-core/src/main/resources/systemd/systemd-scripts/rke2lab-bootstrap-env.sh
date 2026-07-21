#!/usr/bin/env -S bash -exu -o pipefail

: "Provision Nix + Flox + nocloud env so other rke2lab units can rely on yq/dasel/secrets" # @codebase
RKE2LAB_ROOT=${RKE2LAB_ROOT:-/srv/host}
RKE2LAB_SCRIPTS_DIR=${RKE2LAB_ROOT}/systemd-scripts.d
HOME=${HOME:-/root}
export RKE2LAB_ROOT RKE2LAB_SCRIPTS_DIR HOME

: "Source nix-daemon directly; flox is on PATH thanks to rke2lab-flox-install.service"
if [[ ! -r /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh ]]; then
    echo "[rke2lab-bootstrap-env] ERROR: nix-daemon profile missing; rke2lab-flox-install.service must run first" >&2
    exit 1
fi
source /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh

if ! command -v flox >/dev/null 2>&1; then
    echo "[rke2lab-bootstrap-env] ERROR: flox not on PATH after sourcing nix-daemon" >&2
    exit 1
fi

: "Set flox target system for this host"
RKE2_FLOX_SYSTEM="$(uname -m)-linux"
export RKE2_FLOX_SYSTEM

: "Configure direnv to use flox"
direnv:config:generate() {
    mkdir -p "/root/.config/direnv/lib"
    curl -o \
        "/root/.config/direnv/lib/flox.sh" \
        "https://raw.githubusercontent.com/flox/flox-direnv/v1.1.0/direnv.rc"
    cat <<EoConfig | cut -c 3- >"/root/.config/direnv/direnv.toml"
  [whitelist]
  prefix= [ "/home", "/root", "/var/lib/cloud", "/var/lib/rancher/rke2", "${RKE2LAB_ROOT}" ]
EoConfig
}
direnv:config:generate

: "Initialize nocloud flox environment under /var/lib/cloud"
nocloud:env:bootstrap() {
    local FLOX_ENV_DIR="/var/lib/cloud"

    if [[ ! -d "${FLOX_ENV_DIR}/.flox" ]]; then
        mkdir -p "${FLOX_ENV_DIR}"
        flox init --dir="${FLOX_ENV_DIR}"
        flox install --dir="${FLOX_ENV_DIR}" dasel yq-go
    fi

    cat <<'EoFloxCommonProfile' | cut -c 3- | tee "${FLOX_ENV_DIR}/.flox/env/profile-common.sh"
  rke2lab::shell:indirect() {
    local var="$1" value=""

    set +u
    if [[ -n "${BASH_VERSION:-}" ]]; then
      value="${!var-}"
    elif [[ -n "${ZSH_VERSION:-}" ]]; then
      # shellcheck disable=SC2296
      value="${(P)var:-}"
    else
      echo "ERROR: Unsupported shell for secret loading" >&2
      set -u
      return 1
    fi
    set -u

    printf '%s\n' "${value}"
  }

  rke2lab::secret:value() {
    local var="$1" key="$2" val

    val="$( rke2lab::shell:indirect "${var}" )"

    if [[ -z "$val" ]]; then
      val=$( "${FLOX_ENV}/bin/yq" -r "${key}" "${RKE2LAB_SECRETS_FILE}" 2>/dev/null )
      [[ "$val" == "null" ]] && val=""
    fi

    [[ -z "$val" ]] && return
    export "$var=$val"
  }

  RKE2LAB_ROOT=${RKE2LAB_ROOT:-/srv/host}
  RKE2LAB_ENV_DIR=${RKE2LAB_ENV_DIR:-${RKE2LAB_ROOT}/rke2lab-environment.d}
  RKE2LAB_SECRETS_FILE="${RKE2LAB_ROOT}/rke2lab-worktree.d/.secrets"

  : "Ensure RKE2 secrets file is present and readable (read-only source of truth)"
  [[ -s "${RKE2LAB_SECRETS_FILE}" ]] || {
    echo "[rke2lab-bootstrap-env] ERROR: secrets file is missing or empty: ${RKE2LAB_SECRETS_FILE}" >&2
    return 1
  }
  [[ -r "${RKE2LAB_SECRETS_FILE}" ]] || {
    echo "[rke2lab-bootstrap-env] ERROR: secrets file is not readable: ${RKE2LAB_SECRETS_FILE}" >&2
    return 1
  }

  set -a

  : "Source RKE2 environment manifests"
  [[ -d "${RKE2LAB_ENV_DIR}" ]] || {
    echo "[rke2lab-bootstrap-env] ERROR: environment directory missing: ${RKE2LAB_ENV_DIR}" >&2
    return 1
  }
  for env_manifest in "${RKE2LAB_ENV_DIR}"/*.yml "${RKE2LAB_ENV_DIR}"/*.yaml; do
    [[ -f "${env_manifest}" ]] || continue

    kind="$(yq -r '.kind // ""' "${env_manifest}")"
    case "${kind}" in
      ConfigMap)
        source <(yq eval -o=shell '.data // {}' "${env_manifest}")
        ;;
      Secret)
        source <(yq eval -o=shell '((.stringData // {}) * ((.data // {}) | with_entries(.value |= @base64d)))' "${env_manifest}")
        ;;
      *)
        continue
        ;;
    esac
  done

  : "Load RKE2-specific dynamic environment variables"
  ARCH="$(dpkg --print-architecture)"

  : "Backfill secrets from ${RKE2LAB_ROOT}/secrets if not already set (local yq wrapper)"
  rke2lab::secret:value GITHUB_USERNAME '.github.username'
  rke2lab::secret:value GITHUB_PAT '.github.token'
  rke2lab::secret:value DOCKER_CONFIG_JSON '.docker.configJson'
  rke2lab::secret:value TEKTON_GIT_USERNAME '.tekton.git.username'
  rke2lab::secret:value TEKTON_GIT_PASSWORD '.tekton.git.password'
  rke2lab::secret:value TEKTON_DOCKER_CONFIG_JSON '.tekton.docker.configJson'
  rke2lab::secret:value TEKTON_DOCKER_REGISTRY_URL '.tekton.docker.registryUrl'
  rke2lab::secret:value TSKEY_CLIENT_ID '.tailscale.client.id'
  rke2lab::secret:value TSKEY_CLIENT_TOKEN '.tailscale.client.token'
  rke2lab::secret:value TSKEY_API_ID '.tailscale.api.id'
  rke2lab::secret:value TSKEY_API_TOKEN '.tailscale.api.token'
  rke2lab::secret:value TSKEY_OAUTH_ID '.tailscale.oauth.id'
  rke2lab::secret:value TSKEY_OAUTH_TOKEN '.tailscale.oauth.token'
  rke2lab::secret:value CACHIX_AUTH_TOKEN '.cachix.token'

  : "Determine default gateway IP for cluster networking"
  CLUSTER_GATEWAY=$( ip route show default 2>/dev/null |
                      awk '/default via/ { print $3; exit }' ||
                      true )

  set +a
EoFloxCommonProfile

    : "Activate first so dasel/yq from the freshly-installed env are on PATH for the manifest rewrite"
    set +x # Silence flox activation noise
    source <(flox activate --dir="${FLOX_ENV_DIR}")
    set -x
    dasel -i toml -o yaml <"${FLOX_ENV_DIR}/.flox/env/manifest.toml" |
        yq eval '.options = {"systems": [env(RKE2_FLOX_SYSTEM)]}' - |
        yq eval '.profile = { "common": "source ${FLOX_ENV_PROJECT}/.flox/env/profile-common.sh" }' - |
        dasel -i yaml -o toml | tee /tmp/manifest.toml.$$ &&
        mv /tmp/manifest.toml.$$ "${FLOX_ENV_DIR}/.flox/env/manifest.toml"

    : "Re-activate so the updated profile.common is baked into the activation hook for downstream scripts"
    set +x # Silence flox activation noise
    source <(flox activate --dir="${FLOX_ENV_DIR}")
    set -x

    : "Generate nocloud envrc to load environment variables"
    cat >/var/lib/cloud/.envrc <<'EoEnvrc'
  log_status "Loading nocloud environment variables"

  [[ "$FLOX_ENV_PROJECT" != "$PWD" ]] &&
    use flox
EoEnvrc
    mkdir -p /var/lib/cloud/seed/nocloud
    ln -sf /var/lib/cloud/.envrc /var/lib/cloud/seed/nocloud/.envrc
}

nocloud:env:bootstrap

: "Now that /var/lib/cloud/.flox exists, env-load short-circuits its tmpdir fallback"
source "$(dirname "${BASH_SOURCE[0]}")/rke2lab-env-load.sh"

echo "[rke2lab-bootstrap-env] ready"
