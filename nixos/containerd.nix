# rke2's embedded containerd — the flox NRI workload runtime + the zfs snapshotter, and the mount of
# the snapshotter's backing dataset. The declarative form of the former rke2lab-server-pre-start.sh
# (NRI) + config-v3.toml (zfs snapshotter) + rke2lab-configure-containerd-zfs-mount.sh host scripts.
{
  pkgs,
  flox-runtime,
  ...
}:
let
  # The flox containerd runtime, from the runtime/flox sub-flake: an NRI plugin that injects flox
  # environments into workload containers. containerd launches it from plugin_path.
  floxNriPlugin = flox-runtime.packages.${pkgs.stdenv.hostPlatform.system}.flox-nri-plugin;

  # rke2's embedded containerd auto-imports config-v3.toml.d/*.toml — cleaner than overriding the
  # whole config.toml.tmpl. Two drop-ins: NRI enablement + the zfs snapshotter.
  nriDropin = pkgs.writeText "90-nri.toml" ''
    [plugins."io.containerd.nri.v1.nri"]
      disable = false
      plugin_config_path = "/etc/nri/conf.d"
      plugin_path = "/opt/nri/plugins"

    [plugins."io.containerd.cri.v1.runtime".containerd.runtimes.runc.options]
      SystemdCgroup = true
  '';
  zfsDropin = pkgs.writeText "10-zfs.toml" ''
    [plugins."io.containerd.grpc.v1.cri".containerd]
      snapshotter = "zfs"

    [plugins."io.containerd.snapshotter.v1.zfs"]
      root_path = "/var/lib/rancher/rke2/agent/containerd/io.containerd.snapshotter.v1.zfs"
  '';
in
{
  # containerd runtime config placed under /var/lib via tmpfiles (environment.etc can't target
  # /var/lib). This is where the flox containerd runtime lands: the flox-nri-plugin at
  # /opt/nri/plugins/10-flox, which containerd launches to inject flox envs into workload containers.
  systemd.tmpfiles.rules = [
    "d /var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d 0755 root root - -"
    "d /opt/nri/plugins 0755 root root - -"
    "d /etc/nri/conf.d 0755 root root - -"
    "L+ /var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d/10-zfs.toml - - - - ${zfsDropin}"
    "L+ /var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d/90-nri.toml - - - - ${nriDropin}"
    "L+ /opt/nri/plugins/10-flox - - - - ${floxNriPlugin}/bin/flox-nri-plugin"
  ];

  # The zfs snapshotter's backing dataset — a legacy-mountpoint dataset (owned by the incus guest)
  # whose leaf is the NODE NAME (not the hostname): tank/rke2/control-nodes/<node-name>/containerd.
  # The dataset is created out-of-band (hypervisor / a provisioning step). The node name is dynamic
  # (per-node), so the mount cannot be a static systemd.mounts unit — this oneshot reads it from the
  # identity env file and mounts before rke2. mount.zfs comes from pkgs.zfs on the unit PATH.
  systemd.services.rke2lab-zfs-containerd = {
    description = "rke2lab containerd zfs snapshotter dataset mount";
    after = [ "rke2lab-identity.service" ];
    requires = [ "rke2lab-identity.service" ];
    before = [ "rke2-server.service" ];
    requiredBy = [ "rke2-server.service" ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      EnvironmentFile = "/run/rke2lab/node.env";
    };
    path = [
      pkgs.util-linux
      pkgs.zfs
    ];
    script = ''
      set -euo pipefail
      mountpoint=/var/lib/rancher/rke2/agent/containerd/io.containerd.snapshotter.v1.zfs
      dataset="tank/rke2/control-nodes/''${RKE2LAB_NODE_NAME}/containerd"
      install -d -m 0755 "$mountpoint"
      if ! mountpoint -q "$mountpoint"; then
        mount -t zfs "$dataset" "$mountpoint"
      fi
    '';
    preStop = ''
      mountpoint=/var/lib/rancher/rke2/agent/containerd/io.containerd.snapshotter.v1.zfs
      if ${pkgs.util-linux}/bin/mountpoint -q "$mountpoint"; then
        ${pkgs.util-linux}/bin/umount "$mountpoint"
      fi
    '';
  };
}
