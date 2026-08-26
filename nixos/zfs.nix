# ZFS userland on the node (@codebase).
#
# The node is an incus CONTAINER: it does NOT import or manage a zpool (the host,
# bioskop-nixos, delegates the pool), so there is no boot.zfs / supportedFilesystems
# here — only the zfs USERLAND (pkgs.zfs, on PATH via nix-env.nix) operating on the
# delegated pool.
#
# FHS compat: the openebs-zfs (zfs-localpv) CSI node agent drives ZFS by running a
# bundled wrapper that does `chroot /host /sbin/zfs` (else `/usr/sbin/zfs`) — FHS
# paths NixOS keeps in the store instead. Without them the agent fails
# "chroot: /usr/sbin/zfs: No such file or directory" (exit 127): no PVC provisions
# and the ZFSNode CR never syncs its pools (CSIStorageCapacity reports 0, so the
# scheduler refuses WaitForFirstConsumer PVCs). There is no Helm/env knob for the
# zfs path — the wrapper's FHS lookup IS the mechanism — so satisfy its first
# branch by exposing /sbin/{zfs,zpool} as store symlinks (valid inside the chroot,
# since /nix/store is the node's own store).
{
  pkgs,
  ...
}:
{
  systemd.tmpfiles.rules = [
    "d /sbin 0755 root root - -"
    "L+ /sbin/zfs   - - - - ${pkgs.zfs}/bin/zfs"
    "L+ /sbin/zpool - - - - ${pkgs.zfs}/bin/zpool"
  ];
}
