#!/usr/bin/env bash
# Regenerate the committed manifest.lock for flox envs under environment.d/.
#
# Each env's manifest.toml references its workload packages by a RELATIVE flake
# path (`path:../../..#pkg` -> runtime/flox/), and nix resolves `path:` relative
# to the CWD -> so an env MUST be locked from its own directory, not the repo
# root. This script does that for every env (or only the ones named as args).
#
# Run under flox (needs the `flox` CLI on PATH):
#   flox activate -- osgi/.../runtime/flox/lock-envs.sh                 # all envs
#   flox activate -- osgi/.../runtime/flox/lock-envs.sh mesh/headscale  # some
#
# Commit the resulting manifest.lock files: they pin an env like a flake.lock,
# so the node-base image bakes a reproducible activation the container flox
# realises as a cache-hit (no in-container rebuild).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$here/environment.d"

envs=("$@")
if [ "${#envs[@]}" -eq 0 ]; then
    mapfile -t envs < <(cd "$root" && for m in */*/manifest.toml; do echo "${m%/manifest.toml}"; done)
fi

rc=0
for e in "${envs[@]}"; do
    d="$root/$e"
    if [ ! -f "$d/manifest.toml" ]; then
        echo "SKIP $e (no manifest.toml)"
        continue
    fi
    printf 'lock %s ... ' "$e"
    if (cd "$d" && flox lock-manifest manifest.toml) >"$d/manifest.lock.tmp" 2>"$d/.lockerr"; then
        mv "$d/manifest.lock.tmp" "$d/manifest.lock"
        echo "OK ($(wc -c <"$d/manifest.lock") bytes)"
    else
        echo "FAILED"
        sed 's/^/    /' "$d/.lockerr"
        rm -f "$d/manifest.lock.tmp"
        rc=1
    fi
    rm -f "$d/.lockerr"
done
exit "$rc"
