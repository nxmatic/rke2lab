#!/usr/bin/env -S bash -exuo pipefail

[[ -n "${HOME:-}" ]] ||
  export HOME=/root

NIX_PROFILE=/nix/var/nix/profiles/default

: "Load the default Nix profile for current shell session"
source "${NIX_PROFILE}/etc/profile.d/nix-daemon.sh"

: "Ensure git is available via Nix if not already installed"
nix profile add --profile "${NIX_PROFILE}" nixpkgs#git

: "Install Flox via Nix package manager in the default profile for system-wide availability"
nix profile add --profile "${NIX_PROFILE}" github:flox/flox/latest