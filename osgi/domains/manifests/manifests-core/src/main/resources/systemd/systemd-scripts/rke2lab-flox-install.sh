#!/usr/bin/env -S bash -exuo pipefail

[[ -n "${HOME:-}" ]] ||
    export HOME=/root

NIX_PROFILE=/nix/var/nix/profiles/default
NIX_CONF_DIR=/etc/nix
NIX_CONF_FILE="${NIX_CONF_DIR}/nix.conf"
FLOX_CONF_FILE="${NIX_CONF_DIR}/flox.conf"
SECRETS_FILE=/srv/host/rke2lab-worktree.d/.secrets

: "Load the default Nix profile for current shell session"
source "${NIX_PROFILE}/etc/profile.d/nix-daemon.sh"

ensure_flox_nix_include() {
    mkdir -p "${NIX_CONF_DIR}"

    if [[ ! -f "${FLOX_CONF_FILE}" ]]; then
        cat >"${FLOX_CONF_FILE}" <<'EOF'
# Default to use the upstream cache as well as the flox public store.
extra-trusted-substituters = https://cache.flox.dev
extra-trusted-public-keys = flox-cache-public-1:7F4OyH7ZCnFhcze3fJdfyXYLQw/aV7GEed86nQ7IsOs= floxhub-1:0QOAlcobcEvq1mqEf4qAYCaWnTTOXpyoRv/PmqfSixM=

# Note: queries to https://cache.nixos.org via Fastly sometimes encounter
# asymetric routing and thus packet loss. Previously this would cause a very
# long wait for a substitution to fail. Instead, fail quicker and expect the
# user to retry.
connect-timeout = 10
stalled-download-timeout = 30

# Start GC when free disk space is very low.
min-free = 128000000
max-free = 1000000000
EOF
    fi

    if ! grep -Fqx 'include /etc/nix/flox.conf' "${NIX_CONF_FILE}"; then
        cat >>"${NIX_CONF_FILE}" <<'EOF'

# Managed by rke2lab flox installer.
include /etc/nix/flox.conf
EOF
    fi
}

: "Ensure nix.conf includes flox.conf for Flox-specific Nix settings"
ensure_flox_nix_include

: "Ensure git is available via Nix if not already installed"
nix profile add --profile "${NIX_PROFILE}" nixpkgs#git

: "Install Flox via Nix package manager in the default profile for system-wide availability"
nix profile add --profile "${NIX_PROFILE}" github:flox/flox/latest
