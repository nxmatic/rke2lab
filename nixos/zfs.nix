# ZFS userland on the node (@codebase).
#
# The node is an incus CONTAINER: it does NOT import or manage a zpool (the host,
# bioskop-nixos, delegates the pool), so there is no boot.zfs / supportedFilesystems
# here — only the zfs USERLAND (pkgs.zfs, on PATH via nix-env.nix) operating on the
# delegated pool.
#
# FHS compat for the openebs-zfs (zfs-localpv) CSI node agent — SUBTLE. The agent
# drives ZFS via a bundled wrapper that runs `chroot /host /sbin/zfs` (else
# /usr/sbin/zfs), but ONLY after a guard `[ -x /host/sbin/zfs ]`. That guard is
# evaluated in the AGENT's mount namespace BEFORE the chroot, so it resolves a
# symlink's target against the AGENT root — where /nix/store is ABSENT. A
# store-symlink at /sbin/zfs therefore FAILS the guard (proven: even /host/bin/sh
# fails `-x` the same way), and the agent falls through to `chroot /host
# /usr/sbin/zfs` → exit 127 ("could not list zpool", "dataset not present"): no
# PVC provisions and the ZFSNode CR never syncs its pools (CSIStorageCapacity 0,
# scheduler refuses WaitForFirstConsumer PVCs).
#
# `chroot /host /sbin/zfs …` itself WORKS (verified rc=0) — the store zfs runs
# fine inside /host. So the fix is only to make the guard pass: install /sbin/zfs
# (+ /sbin/zpool) as REAL executable files (not store symlinks). `-x` then sees a
# real file; the chroot runs it, and its `#!/bin/sh` + /run/current-system
# indirection both resolve INSIDE /host (where the store IS present). Kept as a
# host-side FHS shim rather than injecting zfs into the agent via flox — the chart
# chroots to the host on purpose, to use the zfs userland that matches the loaded
# kernel module (zfs-kmod); an agent-local zfs would risk userland/kmod skew.
{
  pkgs,
  ...
}:
let
  # A real-file (not store-symlink) FHS wrapper: #!/bin/sh + /run/current-system
  # both resolve inside the openebs chroot (root = the node's /), where the store
  # is present. Generation-independent content, so it never goes stale.
  fhsWrapper =
    bin:
    pkgs.writeTextFile {
      name = "fhs-${bin}";
      executable = true;
      text = ''
        #!/bin/sh
        exec /run/current-system/sw/bin/${bin} "$@"
      '';
    };
in
{
  # install (not tmpfiles L+ / C) so /sbin/{zfs,zpool} are REAL files that pass the
  # agent's `[ -x /host/sbin/zfs ]` guard, overwriting any prior store-symlink.
  system.activationScripts.openebsZfsFhsCompat.text = ''
    install -Dm0755 ${fhsWrapper "zfs"} /sbin/zfs
    install -Dm0755 ${fhsWrapper "zpool"} /sbin/zpool
  '';
}
