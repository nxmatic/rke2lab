#!/usr/bin/env -S bash -exuo pipefail

: "Install Flox via Nix package manager in the default profile for system-wide availability"

nix profile install --profile /nix/var/nix/profiles/default nixpkgs#flox