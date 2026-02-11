#!/usr/bin/env -S bash -exuo pipefail

[[ -n "${HOME:-}" ]] ||
  export HOME=/root

: "Load the default Nix profile for current shell session"
source /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh

: "Ensure git is available via Nix if not already installed"
nix profile add  nixpkgs#git

: "Install Flox via Nix package manager in the default profile for system-wide availability"
nix profile add github:flox/flox/latest