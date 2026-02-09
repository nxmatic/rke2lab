#!/usr/bin/env -S bash -exuo pipefail

: "Install Nix package manager"
export HOME=/root
exec bash -exuo pipefail <(curl --proto '=https' --tlsv1.2 -L https://nixos.org/nix/install) --daemon

