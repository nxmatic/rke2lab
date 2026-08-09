#!/usr/bin/env sh
set -eu

if [ "$#" -ne 4 ]; then
    echo "usage: $0 <workspace> <artifact-dir> <nix-binary> <incus-project>" >&2
    exit 2
fi

workspace="$1"
artifact_dir="$2"
nix_bin="${3:-nix}"
incus_project="$4"

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
# file under flake.lock / flake.nix / nixos/ — a CONTENT digest (not mtimes, not the commit id) of
# exactly what nix will build from. Unchanged inputs + both artifacts still on disk ⇒ the artifacts
# are current, so skip nix entirely. (nix would only RE-EVALUATE anyway — realisation is already
# store-cached — but the git-archive tempdir gives a fresh flake path each run, so nix's own eval
# cache never hits; this gate is what spares that seconds-long NixOS eval on an unchanged tree.)
source_digest="$(git -C "$workspace" ls-tree -r "$source_tree" -- flake.lock flake.nix nixos | sha256sum | awk '{print $1}')"
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
    # No experimental-features and no --accept-flake-config here: nix-command + flakes are already in
    # the builder's global nix config, and the flake's only nixConfig (pure-eval=false) is irrelevant
    # to this PURE node-base eval. (The earlier `--extra-experimental-features nix-command flakes` on
    # an unquoted, word-split flag string mis-parsed "flakes" as the installable `flake:flakes`.) The
    # two remaining flags are single tokens, so word-splitting $nix_flags is safe.
    nix_flags="--no-link --print-out-paths"

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

# Register the freshly-built image in the incus daemon the operator's `incus` speaks to (its default
# remote — on the Mac that is the LAN daemon reached over mDNS; the import uploads the artifacts to
# it, a round-trip accepted on a LAN). One image for now — the homogeneous node-base substrate every
# node boots from — so the alias is hardcoded to node-base (the host GROW ADOPTS it by the SAME
# alias; keep this in sync with rke2lab:image:alias in the stack config).
image_alias="node-base"

# incus derives a SPLIT image's fingerprint as sha256(metadata.tar.xz ++ rootfs.squashfs), metadata
# first (verified empirically against a stored image — it is NOT the sha256 of either file alone).
# Compute it ourselves so the whole flow is deterministic and never parses an incus message: the
# older code guessed sha256(metadata) and only ever produced "Image not found" at alias-create time,
# and incus 7.3's duplicate error carries no fingerprint to recover.
image_fingerprint="$(cat "$artifact_dir/$metadata_name" "$artifact_dir/$rootfs_name" |
    sha256sum | awk '{print $1}')"

# Import only when absent. The nix build is reproducible, so an unchanged tree re-imports
# byte-identical artifacts under this SAME fingerprint — skip the redundant import (and the daemon's
# duplicate rejection) when it is already in the store.
if ! incus image info "$image_fingerprint" --project "$incus_project" >/dev/null 2>&1; then
    import_out="$(incus image import \
        "$artifact_dir/$metadata_name" "$artifact_dir/$rootfs_name" \
        --project "$incus_project" 2>&1)" || {
        echo "failed to import the built image into incus" >&2
        printf '%s\n' "$import_out" >&2
        exit 5
    }
fi

# (Re)point the alias at the fingerprint — the SAME step whether we just imported or found it
# present. Deleting then creating the ALIAS never touches the (possibly in-use) image it points at,
# so this is safe while a prior instance still runs on the old image; and the fingerprint is in hand
# before any delete, so a failure can never orphan the alias.
incus image alias delete "$image_alias" --project "$incus_project" >/dev/null 2>&1 || true
incus image alias create "$image_alias" "$image_fingerprint" --project "$incus_project"
