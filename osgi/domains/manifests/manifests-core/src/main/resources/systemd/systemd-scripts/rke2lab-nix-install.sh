#!/usr/bin/env -S bash -exuo pipefail

[[ -z "${HOME:-}" ]] && export HOME=/root

: "Tuneable retry/wait settings"
RKE2LAB_NIX_NETWORK_RETRIES=${RKE2LAB_NIX_NETWORK_RETRIES:-20}
RKE2LAB_NIX_INSTALL_RETRIES=${RKE2LAB_NIX_INSTALL_RETRIES:-5}
RKE2LAB_NIX_RETRY_SLEEP=${RKE2LAB_NIX_RETRY_SLEEP:-5}
RKE2LAB_NIX_INSTALL_URL=${RKE2LAB_NIX_INSTALL_URL:-https://nixos.org/nix/install}

RKE2LAB_CURL_IP_FAMILY=()

pick_working_ip_family() {
    if curl --proto '=https' --tlsv1.2 --silent --show-error --location --head --max-time 10 "${RKE2LAB_NIX_INSTALL_URL}" >/dev/null 2>&1; then
        RKE2LAB_CURL_IP_FAMILY=()
        return 0
    fi

    if curl --proto '=https' --tlsv1.2 --silent --show-error --location --head --max-time 10 --ipv4 "${RKE2LAB_NIX_INSTALL_URL}" >/dev/null 2>&1; then
        RKE2LAB_CURL_IP_FAMILY=(--ipv4)
        return 0
    fi

    if curl --proto '=https' --tlsv1.2 --silent --show-error --location --head --max-time 10 --ipv6 "${RKE2LAB_NIX_INSTALL_URL}" >/dev/null 2>&1; then
        RKE2LAB_CURL_IP_FAMILY=(--ipv6)
        return 0
    fi

    return 1
}

if [[ -d /etc/nix ]]; then
    : "Nix already installed; continue with idempotent configuration"
else
    : "Wait for basic network readiness (route + HTTPS reachability)"
    attempt=1
    while [[ "${attempt}" -le "${RKE2LAB_NIX_NETWORK_RETRIES}" ]]; do
        if ip route show default >/dev/null 2>&1 && pick_working_ip_family; then
            : "Network is ready for Nix installer download"
            break
        fi

        if [[ "${attempt}" -eq "${RKE2LAB_NIX_NETWORK_RETRIES}" ]]; then
            echo "ERROR: network not ready for Nix install after ${RKE2LAB_NIX_NETWORK_RETRIES} attempts" >&2
            exit 1
        fi

        : "Network not ready yet (attempt ${attempt}/${RKE2LAB_NIX_NETWORK_RETRIES}); retrying"
        sleep "${RKE2LAB_NIX_RETRY_SLEEP}"
        attempt=$((attempt + 1))
    done

    : "Install Nix using official installer with retries"
    install_attempt=1
    while [[ "${install_attempt}" -le "${RKE2LAB_NIX_INSTALL_RETRIES}" ]]; do
        if bash -exuo pipefail <(curl --proto '=https' --tlsv1.2 --show-error -L "${RKE2LAB_CURL_IP_FAMILY[@]}" "${RKE2LAB_NIX_INSTALL_URL}") --daemon --yes; then
            break
        fi

        if [[ "${install_attempt}" -eq "${RKE2LAB_NIX_INSTALL_RETRIES}" ]]; then
            echo "ERROR: Nix installer failed after ${RKE2LAB_NIX_INSTALL_RETRIES} attempts" >&2
            exit 1
        fi

        : "Nix installer failed (attempt ${install_attempt}/${RKE2LAB_NIX_INSTALL_RETRIES}); retrying"
        sleep "${RKE2LAB_NIX_RETRY_SLEEP}"
        install_attempt=$((install_attempt + 1))
    done
fi

: "Verify Nix installation created /etc/nix directory"
if [ ! -d /etc/nix ]; then
    echo "ERROR: Nix installer did not create /etc/nix directory" >&2
    exit 1
fi

source ${NIX_DEFAULT_PROFILE:=/nix/var/nix/profiles/default}/etc/profile.d/nix-daemon.sh
nix --extra-experimental-features "nix-command flakes ca-derivations" \
    profile add --profile "${NIX_DEFAULT_PROFILE}" nixpkgs#yq-go nixpkgs#dasel ||
    {
        echo "ERROR: Failed to install yq-go via Nix" >&2
        exit 1
    }

: "Configure Nix after installation (idempotent - only if not already configured by rke2lab)"
if ! grep -q "BEGIN rke2lab-nix" /etc/nix/nix.conf 2>/dev/null; then
    cat >>/etc/nix/nix.conf <<EOF
# BEGIN rke2lab-nix: Custom configuration for rke2lab environment
allowed-users = *
auto-optimise-store = false
builders =
cores = 0
experimental-features = nix-command flakes ca-derivations
max-jobs = 4
require-sigs = true
sandbox = false
sandbox-fallback = false
substituters = https://aseipp-nix-cache.freetls.fastly.net https://nxmatic.cachix.org https://cache.nixos.org/
system-features = nixos-test benchmark big-parallel kvm gccarch-armv8-a
trusted-public-keys = cache.nixos.org-1:6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY= cache.nixos.org-1:6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY= nxmatic.cachix.org-1:huMghYiwDpPa1PMXHXK4G1Dp4QOZjgsNqxcjf/AjuJ0= cache.nixos.org-1:6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY=
extra-trusted-substituters = https://cache.flox.dev
extra-trusted-public-keys = flox-cache-public-1:7F4OyH7ZCnFhcze3fJdfyXYLQw/aV7GEed86nQ7IsOs=
trusted-substituters = https://cache.nixos.org
trusted-users = root nxmatic root nxmatic root @admin @wheel
extra-platforms = aarch64-darwin
extra-sandbox-paths = /run/binfmt /nix/store/7k1f2qca1mxyrzl6wr74dilrhwbx6qvs-qemu-x86_64-binfmt-P /dev/kvm
keep-outputs = false
keep-derivations = false
keep-failed = false
accept-flake-config = true
access-tokens = github.com=$(yq -r '.github.token' /srv/host/rke2lab-worktree.d/.secrets 2>/dev/null || true)
# END rke2lab-nix
EOF
fi

: "Configure systemd to prepend Nix profile to PATH"
mkdir -p /etc/systemd/system.conf.d
if [ ! -f /etc/systemd/system.conf.d/10-rke2lab-nix.conf ]; then
    cat >/etc/systemd/system.conf.d/10-rke2lab-nix.conf <<EOF
[Manager]
DefaultEnvironment="PATH=/nix/var/nix/profiles/default/bin:$PATH"
EOF
    : "Reload systemd configuration to apply new environment"
    systemctl daemon-reload
fi
