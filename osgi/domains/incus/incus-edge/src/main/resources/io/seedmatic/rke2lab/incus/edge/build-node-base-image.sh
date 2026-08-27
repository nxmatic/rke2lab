#!/usr/bin/env sh
set -eu

if [ "$#" -ne 3 ]; then
    echo "usage: $0 <workspace> <artifact-dir> <nix-binary>" >&2
    exit 2
fi

workspace="$1"
artifact_dir="$2"
nix_bin="${3:-nix}"

export PATH="/run/wrappers/bin:/run/current-system/sw/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

cd "$workspace"

# The artifact dir arrives RELATIVE to the workspace root (the host passes only the subpath and lets
# the builder join it onto the automount root it cd'd into). An already-absolute value is honoured.
case "$artifact_dir" in
/*) ;;
*) artifact_dir="$workspace/$artifact_dir" ;;
esac
mkdir -p "$artifact_dir"

metadata_name="incus.tar.xz"
rootfs_name="rootfs.squashfs"

# Build from the STAGED index, not HEAD: `git write-tree` writes the current index to a tree object
# and prints its SHA. When nothing is staged that tree is byte-identical to HEAD's (so an unamended
# run behaves exactly as before); when node-base edits are `git add`-ed, they are built WITHOUT a
# commit — the iteration loop is stage-and-grow, not commit-and-grow. Unstaged working-tree edits are
# NOT included: stage what you want built. Real git writes the tree (it understands this worktree's
# `relativeworktrees` extension); only nix's libgit2 chokes on the extension, which is why the tree
# is exported to a throwaway dir below rather than fetched as a git+file flake.
source_tree="$(git -C "$workspace" write-tree)"

# Freshness gate over the EXACT build inputs in that tree. `git ls-tree` lists the blob SHA of every
# file under flake.lock / flake.nix / nixos/ AND the flox env catalog (runtime/flox/environment.d) —
# a CONTENT digest (not mtimes, not the commit id) of exactly what nix will build from. The env
# catalog is NOT under nixos/ yet nixos/flox-runtime.nix bakes it into the image by relative path
# (its manifest.lock files pin the workload closures — headscale/kdns/yq/... versions), so an
# env-only edit (add a package, bump a workload + re-lock) MUST invalidate this gate or nix is
# skipped and the stale image is reused. Kept in lock-step with GrowPlanAssembler.imageSourceDigest
# (the OSGi-side twin) and the envCatalog path in nixos/flox-runtime.nix. Unchanged inputs + both
# artifacts still on disk ⇒ the artifacts are current, so skip nix entirely.
source_digest="$(git -C "$workspace" ls-tree -r "$source_tree" -- flake.lock flake.nix nixos osgi/domains/manifests/manifests-core/src/main/resources/runtime/flox/environment.d | sha256sum | awk '{print $1}')"
checksum_file="$artifact_dir/.image.checksum.sha256"

if [ -f "$checksum_file" ] && [ "$(cat "$checksum_file")" = "$source_digest" ] &&
    [ -f "$artifact_dir/$metadata_name" ] && [ -f "$artifact_dir/$rootfs_name" ]; then
    echo "node-base sources unchanged ($source_digest) — reusing on-disk artifacts, skipping nix build"
else
    # Export the staged tree (computed above) with real git into a throwaway dir, and build THAT as a
    # path-flake: no git+file fetch (which libgit2 would reject over the `relativeworktrees`
    # extension), and exactly the tree the freshness digest was taken over. flake.lock rides in the
    # archive, so inputs stay pinned.
    src_dir="$(mktemp -d)"
    trap 'rm -rf "$src_dir"' EXIT
    git -C "$workspace" archive --format=tar "$source_tree" | tar -x -C "$src_dir"

    # The homogeneous NixOS substrate every RKE2 node boots from. Build its two Incus artifacts: nix
    # realises them into /nix/store (local, content-addressed) — no tmpfs scratch and no root, unlike
    # distrobuilder. Only the two finished files are published below.
    attr="nixosConfigurations.rke2-node-base.config.system.build"

    # The image now bakes flox ENVS (nixos/flox-runtime.nix), which import flox's
    # buildenv.nix — that does `builtins.storePath` on the env lock's realised
    # outputs. Two consequences for the node-base eval, both handled here:
    #
    #  1. It is no longer PURE → we pass `--impure` (buildenv.nix reads
    #     `builtins.currentSystem` + `builtins.storePath`, both removed in pure
    #     eval; `--accept-flake-config` alone does NOT lift them here — the flake's
    #     `nixConfig.pure-eval=false` is not applied for this path-flake eval), plus
    #     `--system aarch64-linux` so `builtins.currentSystem` keys correctly even
    #     when this script evals on a darwin host. `--accept-flake-config` stays for
    #     the flake's substituters/trusted config.
    #  2. storePath requires the outputs PRESENT. Catalog packages substitute from
    #     cache.nixos.org during eval, but our flake-built env packages (e.g.
    #     kdns-debug) have no substituter → REALISE them first, on the aarch64-linux
    #     builder, yielding exactly the paths the committed manifest.lock pins.
    #  3. `--system aarch64-linux` also makes nix believe the LOCAL (darwin) machine
    #     is aarch64-linux, so it would try to build the small derivations LOCALLY
    #     and fail ("executing bash: Undefined error: 0" — a linux ELF on darwin).
    #     `--max-jobs 0` forbids local builds → everything offloads to the configured
    #     aarch64-linux builder (bioskop-nixos), which is native. (Before --system,
    #     nix knew local=darwin != linux and offloaded on its own.)
    nix_flags="--no-link --print-out-paths --impure --accept-flake-config --system aarch64-linux --max-jobs 0"

    # (1) realise each env's non-substitutable flake packages before the image eval:
    # buildenv.nix does `builtins.storePath` on them, which needs them present.
    # DERIVED from every LOCKED env's manifest (its `flake = "path:..#<pkg>"` refs)
    # so the set never drifts from the catalog — add an env (manifest.toml + a
    # committed manifest.lock via lock-envs.sh) and its flake packages are realised
    # automatically. Catalog packages (bash/kubectl/...) are substitutable and need
    # no pre-realise; only the local flake packages do — which is exactly what the
    # `flake = "path:..#pkg"` refs select (e.g. kdns prod, sourced via the overlay,
    # is correctly NOT realised; kdns-debug, a flake ref, is).
    flox_flake="$src_dir/osgi/domains/manifests/manifests-core/src/main/resources/runtime/flox"

    # (0) Self-heal env locks in the EXPORTED tree before baking. Each env's
    # manifest.lock pins its workload package outputs; a flake/nixpkgs move rebuilds
    # them, and an un-re-locked env then points at a path the fresh bake never
    # produced (the tailscale-debug drift). Re-locking is deterministic (flake.lock
    # pins the inputs) but EVAL-heavy — one nixpkgs eval per env — so gate it on a
    # content hash of everything under runtime/flox EXCEPT the generated manifest.lock
    # files (i.e. flake.nix / flake.lock / manifest.toml / scripts). Unchanged ⇒ reuse
    # the locks cached from the last re-lock (zero eval); changed ⇒ re-lock all envs +
    # refresh the cache. Excluding manifest.lock avoids a fixpoint (the locks are part
    # of the tree). Cache + hash live in the artifact dir (persist across builds).
    # Ephemeral: only the tmpfs copy is touched; needs flox on PATH (inherited from the
    # seed's `flox activate`); absent ⇒ fall back to the committed locks as-is.
    flox_defs_hash="$(git -C "$workspace" ls-tree -r "$source_tree" -- \
        osgi/domains/manifests/manifests-core/src/main/resources/runtime/flox |
        grep -v '/manifest\.lock' | sha256sum | awk '{print $1}')"
    lock_cache="$artifact_dir/.envlock-cache.tar"
    lock_hash_file="$artifact_dir/.envlock-defs.sha256"
    if [ -f "$lock_hash_file" ] && [ "$(cat "$lock_hash_file")" = "$flox_defs_hash" ] && [ -f "$lock_cache" ]; then
        echo "flox env locks: flake definitions unchanged ($flox_defs_hash) — reusing cached locks"
        tar -xf "$lock_cache" -C "$flox_flake/environment.d"
    elif command -v flox >/dev/null 2>&1; then
        echo "flox env locks: flake definitions changed — re-locking all envs"
        (cd "$flox_flake" && bash lock-envs.sh)
        (cd "$flox_flake/environment.d" && tar -cf "$lock_cache" $(find . -name manifest.lock))
        printf '%s' "$flox_defs_hash" >"$lock_hash_file"
    else
        echo "WARNING: flox not on PATH — skipping env-lock self-heal; using committed locks as-is" >&2
    fi

    env_pkgs="$(
        for lock in "$flox_flake"/environment.d/*/*/manifest.lock; do
            [ -f "$lock" ] || continue
            grep -hoE 'flake = "path:[^"#]*#[A-Za-z0-9_-]+"' "$(dirname "$lock")/manifest.toml" || true
        done | sed -E 's/.*#([A-Za-z0-9_-]+)".*/\1/' | sort -u
    )"
    for env_pkg in $env_pkgs; do
        # `^*` = ALL outputs, not just the default `out`. A multi-output package
        # (e.g. tailscale-debug ships out + derper) otherwise leaves the non-default
        # outputs unrealised, and buildenv's storePath on them at activation fails
        # → the container re-realises from the (absent) source flake and dies.
        echo "realising flox env package (all outputs): $env_pkg"
        $nix_bin build $nix_flags "$flox_flake#$env_pkg^*" >/dev/null
    done

    # (2) build the two node-base artifacts.
    metadata_out="$($nix_bin build $nix_flags "$src_dir#$attr.metadata")"
    squashfs_out="$($nix_bin build $nix_flags "$src_dir#$attr.squashfs")"

    # Locate the artifacts inside the nix outputs, layout-robust: the metadata is a *.tar.xz under the
    # metadata output, the rootfs a *.squashfs under the squashfs output (or the output path itself).
    metadata_src="$(find "$metadata_out" -name '*.tar.xz' 2>/dev/null | head -n1)"
    squashfs_src="$(find "$squashfs_out" -name '*.squashfs' 2>/dev/null | head -n1)"
    if [ -z "$squashfs_src" ] && [ -f "$squashfs_out" ]; then
        squashfs_src="$squashfs_out"
    fi

    if [ -z "$metadata_src" ] || [ -z "$squashfs_src" ]; then
        echo "nix build did not yield a metadata tarball + rootfs squashfs" >&2
        echo "  metadata output: $metadata_out" >&2
        echo "  squashfs output: $squashfs_out" >&2
        exit 3
    fi

    cp -f "$metadata_src" "$artifact_dir/$metadata_name"
    cp -f "$squashfs_src" "$artifact_dir/$rootfs_name"
    chmod a+r "$artifact_dir/$metadata_name" "$artifact_dir/$rootfs_name"

    # Record the inputs digest beside the artifacts so the next run's freshness gate can trust them.
    printf '%s\n' "$source_digest" >"$checksum_file"
fi

# Build-only: the two artifacts ($metadata_name + $rootfs_name) and their freshness checksum are the
# whole output. The image is NOT imported here — that is the incus PROVIDER's job (the host GROW
# declares an `Image` resource sourcing these files, so the provider orders Project → Image → Instance
# and the import rides the provider's own channel). This keeps the build a pure artifact producer with
# no incus coupling and no out-of-graph side effect. See
# docs/architecture/nixos-substrate/node-bootstrap-delivery.adoc's sibling — the substrate model.
