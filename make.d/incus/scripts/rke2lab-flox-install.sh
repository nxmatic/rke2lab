#!/usr/bin/env -S bash -exuo pipefail

[[ -n "${HOME:-}" ]] ||
  export HOME=/root

: "Load Nix profile for current shell session"
if [[ ! -e "/etc/profile.d/nix-profile.sh" ]]; then
    echo "ERROR: Nix profile script not found at expected location" >&2
    exit 1
fi
source /etc/profile.d/nix-profile.sh

: "Ensure git is available via Nix if not already installed"
command -v git &> /dev/null || 
  nix profile add  nixpkgs#git

: "Install Flox via Nix package manager in the default profile for system-wide availability"
nix profile add github:flox/flox/latest