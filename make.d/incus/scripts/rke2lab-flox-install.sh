#!/usr/bin/env -S bash -exuo pipefail

: "Install Flox via Nix package manager"
: "Install to default profile for system-wide availability"

nix profile install --profile /nix/var/nix/profiles/default github:flox/floxpkgs#flox