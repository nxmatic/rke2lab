#!/usr/bin/env -S bash -exuo pipefail

: "Install Flox via Nix package manager"
: "Note: Nix profile is automatically sourced via /etc/bash.bashrc"
nix profile add github:flox/floxpkgs#flox