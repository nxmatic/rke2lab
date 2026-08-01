#!/usr/bin/env sh
set -euo pipefail

if [ "$#" -ne 5 ]; then
    echo "usage: $0 <workspace> <config-path> <artifact-dir> <builder-binary> <incus-project>" >&2
    exit 2
fi

remote_workspace="$1"
remote_config_path="$2"
remote_artifact_dir="$3"
binary="$4"
incus_project="$5"

export PATH="/run/wrappers/bin:/run/current-system/sw/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

if [ -x /run/wrappers/bin/sudo ]; then
    SUDO_BIN=/run/wrappers/bin/sudo
else
    SUDO_BIN=sudo
fi

cd "$remote_workspace"

# The artifact dir arrives RELATIVE to the workspace root: the host passes only the subpath and
# lets the builder join it onto the automount root it cd'd into. Resolve it here (an already
# absolute value is honoured as-is, defensively).
case "$remote_artifact_dir" in
/*) ;;
*) remote_artifact_dir="$remote_workspace/$remote_artifact_dir" ;;
esac

run_as_root() {
    if [ "$(id -u)" = 0 ]; then
        "$@"
        return
    fi

    "$SUDO_BIN" -n "$@"
}

mkdir -p "$remote_artifact_dir"

# Build scratch is a LOCAL tmpfs on the builder, never the (possibly automount) artifact dir:
# mounting tmpfs over an autofs/NFS path is unreliable and distrobuilder is I/O-heavy. Only the
# finished artifacts are published to the artifact dir below. Mounted fresh here and released on
# exit; the findmnt check still absorbs a stale mount a crashed prior run may have left behind.
build_tmpfs_dir="/var/tmp/distrobuilder.$(basename "$remote_artifact_dir").tmpfs"
run_as_root mkdir -p "$build_tmpfs_dir"

# Release the scratch on exit (success or failure): unmount the tmpfs, then drop the mount point.
# rmdir (not rm -rf) so a still-busy mount is left intact rather than having its contents nuked.
cleanup() {
    run_as_root umount "$build_tmpfs_dir" 2>/dev/null || true
    run_as_root rmdir "$build_tmpfs_dir" 2>/dev/null || true
}
trap cleanup EXIT

build_fs_type=""
if command -v findmnt >/dev/null 2>&1; then
    build_fs_type=$(findmnt -rno FSTYPE --target "$build_tmpfs_dir" 2>/dev/null || true)
fi

if [ "$build_fs_type" != "tmpfs" ]; then
    tmpfs_size="${DISTROBUILDER_TMPFS_SIZE:-4G}"
    run_as_root mount -t tmpfs -o "size=$tmpfs_size,mode=0755" tmpfs "$build_tmpfs_dir"
    if command -v findmnt >/dev/null 2>&1; then
        build_fs_type=$(findmnt -rno FSTYPE --target "$build_tmpfs_dir" 2>/dev/null || true)
    fi
fi

if [ "$build_fs_type" != "tmpfs" ]; then
    echo "build scratch directory must be tmpfs: $build_tmpfs_dir (detected: ${build_fs_type:-unknown})" >&2
    exit 4
fi

# Resolve the builder binary from the workspace flake (a pinned, cached package
# output — the #distrobuilder mirror of #incus-client) rather than `flox activate`.
# Activating the whole rke2lab dev env just to put distrobuilder on PATH drags its
# k8s include in, which on the builder means a from-source ceph-client build that
# stalls the image build for a long time. `nix build` pulls ONLY distrobuilder +
# its closure from the cache; we realise it as the invoking user, then run just the
# resulting binary as root (distrobuilder's build-incus needs root for the rootfs).
if command -v nix >/dev/null 2>&1; then
    builder_bin="$(nix build --no-link --print-out-paths \
        --extra-experimental-features 'nix-command flakes' \
        "$remote_workspace#$binary")/bin/$binary"
    run_as_root "$builder_bin" build-incus "$remote_config_path" "$build_tmpfs_dir"
else
    run_as_root "$binary" build-incus "$remote_config_path" "$build_tmpfs_dir"
fi

metadata_name="incus.tar.xz"
rootfs_name="rootfs.squashfs"
build_metadata_path="$build_tmpfs_dir/$metadata_name"
build_rootfs_path="$build_tmpfs_dir/$rootfs_name"

if [ ! -f "$build_metadata_path" ] || [ ! -f "$build_rootfs_path" ]; then
    echo "expected distrobuilder artifacts were not produced in $build_tmpfs_dir" >&2
    exit 3
fi

# Publish the finished artifacts to the (possibly automount) canonical output directory. The build
# ran in the local tmpfs scratch above, so this cp is the only write that lands on the artifact dir.
cp -f "$build_metadata_path" "$remote_artifact_dir/$metadata_name"
cp -f "$build_rootfs_path" "$remote_artifact_dir/$rootfs_name"
chmod a+r "$remote_artifact_dir/$metadata_name" "$remote_artifact_dir/$rootfs_name"

# Register the freshly-built image DIRECTLY in the local incus daemon (this builder host IS the
# incus daemon host — bioskop-nixos), so the Pulumi client never re-uploads bytes the daemon can
# already read locally. The image alias is the artifact dir's own leaf (the host lays artifacts out
# as <sharedFolder>/<alias>). incus talks to the local daemon over its unix socket here (the same
# daemon the Pulumi provider reaches over https), so the host GROW then ADOPTS this image by alias
# instead of declaring an uploading `sourceFile`.
image_alias="$(basename "$remote_artifact_dir")"

# incus' split-image fingerprint is the SHA-256 of the metadata tarball — use it to detect an image
# already present (a re-point without a rebuild) and skip a redundant import. Trust the fingerprint
# incus actually reports over this guess when we do import.
image_fingerprint="$(sha256sum "$remote_artifact_dir/$metadata_name" | awk '{print $1}')"
if ! incus image info "$image_fingerprint" --project "$incus_project" >/dev/null 2>&1; then
    import_out="$(incus image import \
        "$remote_artifact_dir/$metadata_name" "$remote_artifact_dir/$rootfs_name" \
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
