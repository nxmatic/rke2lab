#!/usr/bin/env bash
set -euo pipefail

# @codebase
# Load RKE2Lab environment variables from sectioned ConfigMap/Secret manifests.

set -a
RKE2LAB_SCRIPTS_DIR=${RKE2LAB_ROOT:=/srv/host}/systemd-scripts.d
RKE2LAB_SYSTEMD_LIBEXEC_DIR=${RKE2LAB_SYSTEMD_LIBEXEC_DIR:-${RKE2LAB_ROOT}/systemd-libexec.d}
HOME=/root
set +a

if [[ ! -r /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh ]]; then
    : "Install Nix Daemon and CLI tools to access yq"
    ${RKE2LAB_SCRIPTS_DIR}/rke2lab-nix-install.sh
fi
source /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh

if ! command -v flox >/dev/null 2>&1; then
    : "Install Flox to access yq in a temporary environment"
    ${RKE2LAB_SCRIPTS_DIR}/rke2lab-flox-install.sh
fi

rke2lab::flox:ensure_manifest_tools() {
    if [[ -d /var/lib/cloud/.flox ]]; then
        : "Activate cloud flox environment for yq and dasel availability"
        set +x # Silence flox activation noise
        source <(flox activate --dir /var/lib/cloud)
        set -x
    fi

    if command -v yq >/dev/null 2>&1 && command -v dasel >/dev/null 2>&1; then
        return 0
    fi

    local flox_tmpdir
    flox_tmpdir=$(mktemp -d)

    flox config --set disable_metrics true
    flox init --dir="${flox_tmpdir}"
    flox install --dir="${flox_tmpdir}" yq-go dasel
    set +x # Silence flox activation noise
    source <(flox activate --dir="${flox_tmpdir}")
    set -x
}

rke2lab::flox:configure_auth_from_secrets() {
    local repo_root secrets_file flox_token cachix_token cachix_cache_name config_dir config_file tmp_file

    repo_root=${RKE2LAB_REPO_ROOT:-/srv/host/rke2lab-worktree.d}
    secrets_file="${repo_root}/.secrets"
    [[ -r "${secrets_file}" ]] || return 0

    flox_token="$(yq eval -r '.flox.token // ""' "${secrets_file}" 2>/dev/null || true)"
    cachix_token="$(yq eval -r '.cache.nxmatic.token // ""' "${secrets_file}" 2>/dev/null || true)"
    cachix_cache_name="$(yq eval -r '.cache.nxmatic.name // "nxmatic"' "${secrets_file}" 2>/dev/null || true)"

    if [[ -n "${cachix_token}" ]]; then
        export CACHIX_AUTH_TOKEN="${cachix_token}"
        export RKE2LAB_CACHIX_CACHE_NAME="${cachix_cache_name}"
    fi

    [[ -n "${flox_token}" ]] || return 0

    config_dir=${FLOX_CONFIG_DIR:-/root/.config/flox}
    config_file="${config_dir}/flox.toml"
    mkdir -p "${config_dir}"
    touch "${config_file}"

    if ! dasel query --root -i toml -o toml '.' <"${config_file}" >/dev/null 2>&1; then
        cat >"${config_file}" <<'EOF'
disable_metrics = true
floxhub_token = ""
EOF
    fi

    if ! grep -q '^[[:space:]]*disable_metrics[[:space:]]*=' "${config_file}"; then
        echo 'disable_metrics = true' >>"${config_file}"
    fi
    if ! grep -q '^[[:space:]]*floxhub_token[[:space:]]*=' "${config_file}"; then
        echo "floxhub_token = ''" >>"${config_file}"
    fi

    tmp_file="$(mktemp)"
    if ! dasel query --root -i toml -o toml 'disable_metrics=true' <"${config_file}" >"${tmp_file}"; then
        cat >"${tmp_file}" <<'EOF'
disable_metrics = true
floxhub_token = ""
EOF
    fi
    if ! dasel query --root -i toml -o toml "floxhub_token=\"${flox_token}\"" <"${tmp_file}" >"${config_file}"; then
        cat >"${config_file}" <<EOF
disable_metrics = true
floxhub_token = "${flox_token}"
EOF
    fi
    rm -f "${tmp_file}"
}

rke2lab::flox:ensure_manifest_tools
rke2lab::flox:configure_auth_from_secrets

rke2lab::env:load() {
    # The env-config sections are materialised into ONE shell file in the scripts dir by incus's
    # BootstrapHostAssetMaterializer (SHELL_FILE strategy) — already wrapped set -a … set +a, so
    # sourcing it auto-exports every rke2lab variable. Filename is the convention shared with
    # EnvConfigHostAssetProvider.ENV_FILE; keep the two in sync.
    local env_file="${RKE2LAB_SCRIPTS_DIR:-/srv/host/systemd-scripts.d}/rke2lab-environment.sh"

    if [[ ! -r "${env_file}" ]]; then
        echo "[rke2lab-env] missing environment file: ${env_file}" >&2
        return 1
    fi

    source "${env_file}"
}

rke2lab::env:load
