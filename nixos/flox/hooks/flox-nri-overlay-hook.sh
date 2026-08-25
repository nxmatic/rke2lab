#!/usr/bin/env -S bash -euxo pipefail
# OCI CreateContainer hook: mount an overlayfs on a path in the container rootfs.
#
# Runs in the container's mount namespace before pivot_root, so mounts performed
# here persist into the running container after pivot_root. (CreateRuntime hooks
# run in the host namespace and don't propagate in because the container ns is
# unshared with rprivate propagation.)
#
# Reads the OCI container state JSON on stdin (per OCI runtime spec) to extract
# the bundle path; the container rootfs is "${bundle}/rootfs".
#
# Layout: scratch dirs for every overlay are grouped under /.overlays.d/<name>
# inside the container rootfs:
#   /.overlays.d/<name>/lower         bind of <lower-source> from the host (ro)
#   /.overlays.d/<name>/rw            single tmpfs hosting upper/ and work/
#                                     (overlayfs requires upperdir and workdir
#                                     on the same mount)
#   /.overlays.d/<name>/rw/upper      upperdir
#   /.overlays.d/<name>/rw/work       workdir
# An overlay is then mounted at <target>.
#
# Usage: flox-nri-overlay-hook.sh <name> <lower-source> <target>
#   <name>          short identifier for the overlay; used as the subfolder
#                   name under /.overlays.d/
#   <lower-source>  host filesystem path used as the read-only lower layer
#   <target>        absolute path inside the container rootfs for the overlay
#                   mountpoint
#
# Logging: the OCI runtime (containerd/runc) captures the hook's stdout/stderr and
# surfaces it on the pod's events + containerd log, so we log there directly. We do
# NOT reroute through logger(1)/journald: a CreateContainer hook runs in the
# container's restricted mount namespace before pivot_root, where /dev/log and the
# /dev/fd process-substitution plumbing may be absent — `exec > >(logger …)` then
# fails the hook (exit 127) before it can mount anything.

overlay_name="$1"
lower_source="$2"
target_rel="$3"

state="$(cat)"
bundle="$(printf '%s' "$state" | sed -n 's/.*"bundle"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
container_id="$(printf '%s' "$state" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"

echo "hook invoked container=${container_id} bundle=${bundle} name=${overlay_name} lower=${lower_source} target=${target_rel}"

if [ -z "$bundle" ]; then
    echo "ERROR: could not parse bundle path from OCI state" >&2
    exit 1
fi

rootfs="${bundle%/}/rootfs"
if [ ! -d "$rootfs" ]; then
    echo "ERROR: rootfs does not exist: ${rootfs}" >&2
    exit 1
fi

target="${rootfs}${target_rel}"
overlay_root="${rootfs}/.overlays.d/${overlay_name}"
lower="${overlay_root}/lower"
rw="${overlay_root}/rw"
upper="${rw}/upper"
work="${rw}/work"

mkdir -p "$target" "$lower" "$rw"

mount --bind "$lower_source" "$lower"
mount -o remount,bind,ro "$lower"

# Single tmpfs hosting both upper/ and work/ — overlayfs requires upperdir and
# workdir to reside on the same mount.
mount -t tmpfs -o mode=0755,size=2g tmpfs "$rw"
mkdir -p "$upper" "$work"

# Use container-relative paths (from the perspective of the future root after pivot_root).
# The hook runs before pivot_root, so we must chroot into ${rootfs} first to make paths
# relative to the container's future root. Otherwise overlayfs records absolute host paths
# like /run/k3s/containerd/.../rootfs/.overlays.d/... which are invalid after pivot_root.
container_lower="/.overlays.d/${overlay_name}/lower"
container_upper="/.overlays.d/${overlay_name}/rw/upper"
container_work="/.overlays.d/${overlay_name}/rw/work"

chroot "$rootfs" mount -t overlay overlay \
    -o "lowerdir=${container_lower},upperdir=${container_upper},workdir=${container_work}" \
    "${target_rel}"

exit 0
