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

# The worktree carries the git `relativeworktrees` extension, which nix's libgit2 cannot parse — a
# `git+file://` fetch of it fails outright. Export the tracked tree at HEAD with real git (which DOES
# understand the extension) into a throwaway dir, and build THAT as a path-flake: no git fetch, and
# always exactly what is committed. flake.lock rides in the archive, so the inputs stay pinned.
src_dir="$(mktemp -d)"
trap 'rm -rf "$src_dir"' EXIT
git -C "$workspace" archive --format=tar HEAD | tar -x -C "$src_dir"

# The homogeneous NixOS substrate every RKE2 node boots from. Build its two Incus artifacts: nix
# realises them into /nix/store (local, content-addressed) — no tmpfs scratch and no root, unlike
# distrobuilder. Only the two finished files are published below.
attr="nixosConfigurations.rke2-node-base.config.system.build"
# No experimental-features and no --accept-flake-config here: nix-command + flakes are already in the
# builder's global nix config, and the flake's only nixConfig (pure-eval=false) is irrelevant to this
# PURE node-base eval. (The earlier `--extra-experimental-features nix-command flakes` on an unquoted,
# word-split flag string mis-parsed "flakes" as the installable `flake:flakes` and failed the build.)
# The two remaining flags are single tokens, so word-splitting $nix_flags is safe.
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

metadata_name="incus.tar.xz"
rootfs_name="rootfs.squashfs"
cp -f "$metadata_src" "$artifact_dir/$metadata_name"
cp -f "$squashfs_src" "$artifact_dir/$rootfs_name"
chmod a+r "$artifact_dir/$metadata_name" "$artifact_dir/$rootfs_name"

# Register the freshly-built image DIRECTLY in the local incus daemon (this builder host IS the incus
# daemon host — bioskop-nixos), so the Pulumi client never re-uploads bytes the daemon can already
# read locally. The alias is the artifact dir's own leaf (the host lays artifacts out as
# <sharedFolder>/<alias>); the host GROW then ADOPTS this image by alias.
image_alias="$(basename "$artifact_dir")"

# incus' split-image fingerprint is the SHA-256 of the metadata tarball — use it to detect an image
# already present (a re-point without a rebuild) and skip a redundant import. Trust the fingerprint
# incus actually reports over this guess when we do import.
image_fingerprint="$(sha256sum "$artifact_dir/$metadata_name" | awk '{print $1}')"
if ! incus image info "$image_fingerprint" --project "$incus_project" >/dev/null 2>&1; then
    import_out="$(incus image import \
        "$artifact_dir/$metadata_name" "$artifact_dir/$rootfs_name" \
        --project "$incus_project" 2>&1)" || {
        # Tolerate an idempotent 'already exists' (the image is present); fail on anything else.
        printf '%s\n' "$import_out" | grep -qi 'already exists' || {
            echo "failed to import the built image into the local incus daemon" >&2
            printf '%s\n' "$import_out" >&2
            exit 5
        }
    }
    imported_fp="$(printf '%s\n' "$import_out" | grep -oE '[0-9a-f]{64}' | head -n1)"
    [ -n "$imported_fp" ] && image_fingerprint="$imported_fp"
fi

# (Re)point the alias at this fingerprint. Deleting/creating the ALIAS never touches the (possibly
# in-use) image it pointed at, so this is safe while a prior instance still runs on the old image.
incus image alias delete "$image_alias" --project "$incus_project" >/dev/null 2>&1 || true
incus image alias create "$image_alias" "$image_fingerprint" --project "$incus_project"
