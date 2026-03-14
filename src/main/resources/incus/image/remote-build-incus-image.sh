#!/usr/bin/env sh
set -eu

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <workspace> <config-path> <artifact-dir> <builder-binary>" >&2
  exit 2
fi

remote_workspace="$1"
remote_config_path="$2"
remote_artifact_dir="$3"
binary="$4"

export PATH="/run/wrappers/bin:/run/current-system/sw/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

if [ -x /run/wrappers/bin/sudo ]; then
  SUDO_BIN=/run/wrappers/bin/sudo
else
  SUDO_BIN=sudo
fi

cd "$remote_workspace"

run_as_root() {
  if [ "$(id -u)" = 0 ]; then
    "$@"
    return
  fi

  "$SUDO_BIN" -n "$@"
}

run_as_root mkdir -p "$remote_artifact_dir"

build_tmpfs_dir="$(dirname "$remote_artifact_dir")/.$(basename "$remote_artifact_dir").tmpfs"
run_as_root mkdir -p "$build_tmpfs_dir"

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

if command -v flox >/dev/null 2>&1; then
  if [ "$(id -u)" = 0 ]; then
    flox activate -- "$binary" build-incus "$remote_config_path" "$build_tmpfs_dir"
  else
    flox activate -- "$SUDO_BIN" -n "$binary" build-incus "$remote_config_path" "$build_tmpfs_dir"
  fi
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

# Publish artifacts to the canonical output directory (original name).
cp -f "$build_metadata_path" "$remote_artifact_dir/$metadata_name"
cp -f "$build_rootfs_path" "$remote_artifact_dir/$rootfs_name"
chmod a+r "$remote_artifact_dir/$metadata_name" "$remote_artifact_dir/$rootfs_name"

# If canonical output itself is over-mounted tmpfs, keep a persistent NFS backup.
output_fs_type=""
if command -v findmnt >/dev/null 2>&1; then
  output_fs_type=$(findmnt -rno FSTYPE --target "$remote_artifact_dir" 2>/dev/null || true)
fi

if printf '%s\n' "$remote_artifact_dir" | grep -Eq '^/net/' && [ "$output_fs_type" = "tmpfs" ]; then
  nfs_artifact_dir="${remote_artifact_dir}.nfs"
  mkdir -p "$nfs_artifact_dir"
  cp -f "$build_metadata_path" "$nfs_artifact_dir/$metadata_name"
  cp -f "$build_rootfs_path" "$nfs_artifact_dir/$rootfs_name"
  chmod a+r "$nfs_artifact_dir/$metadata_name" "$nfs_artifact_dir/$rootfs_name"
fi
