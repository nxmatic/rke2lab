# rke2lab node-base — the homogeneous NixOS substrate every RKE2 node shares.
# Role (server/agent) and per-node identity (node-ip, hostname, token) are layered on top;
# this module is what is common to all nodes. See docs/architecture/nixos-substrate/target-vision.adoc.
#
# Iteration 1 (path B): stock rke2 server, dual-stack, flox baked. Proven end-to-end by the spike
# (image builds, boots in Incus, rke2-server + containerd come up). Next iterations add the
# flox-nri-plugin (containerd NRI, from the runtime/flox sub-flake), cilium, and the zfs snapshotter.
{
  config,
  lib,
  pkgs,
  flox,
  flox-runtime,
  ...
}:
let
  # The flox containerd runtime, from the runtime/flox sub-flake: an NRI plugin that injects
  # flox environments into workload containers. containerd launches it from plugin_path.
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
  system.stateVersion = "24.11";
  networking.hostName = lib.mkDefault "rke2node";

  # Lab node: no host firewall in the way of the rke2 control-plane / cluster ports.
  networking.firewall.enable = false;

  # RKE2 server. cidrs baked DUAL-STACK — the spike proved a single-family node-ip crashes
  # kube-apiserver ("service IP family must match public address family"). IPv4 primary,
  # IPv6 secondary. cni="none" for now (cilium arrives with our manifests); the node stays
  # NotReady until a CNI is present, which is expected.
  services.rke2 = {
    enable = true;
    role = "server";
    cni = "none";
  };
  environment.etc."rancher/rke2/config.yaml.d/10-dualstack.yaml".text = ''
    cluster-cidr: 10.42.0.0/16,fd00:42::/56
    service-cidr: 10.43.0.0/16,fd00:43::/112
  '';

  # containerd runtime config (rke2's embedded containerd) — the declarative form of
  # rke2lab-server-pre-start.sh (NRI) + config-v3.toml (zfs snapshotter) +
  # rke2lab-configure-containerd-zfs-mount.sh. Placed under /var/lib via tmpfiles (environment.etc
  # can't target /var/lib). This is where the flox containerd runtime lands: the flox-nri-plugin at
  # /opt/nri/plugins/10-flox, which containerd launches to inject flox envs into workload containers.
  systemd.tmpfiles.rules = [
    "d /var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d 0755 root root - -"
    "d /opt/nri/plugins 0755 root root - -"
    "d /etc/nri/conf.d 0755 root root - -"
    "L+ /var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d/10-zfs.toml - - - - ${zfsDropin}"
    "L+ /var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d/90-nri.toml - - - - ${nriDropin}"
    "L+ /opt/nri/plugins/10-flox - - - - ${floxNriPlugin}/bin/flox-nri-plugin"
  ];

  # The zfs snapshotter's backing dataset. Per-node dataset name (hostName); must mount before rke2.
  # The dataset itself is created out-of-band (hypervisor / a provisioning step), as on Debian.
  systemd.mounts = [
    {
      what = "tank/rke2/control-nodes/${config.networking.hostName}/containerd";
      where = "/var/lib/rancher/rke2/agent/containerd/io.containerd.snapshotter.v1.zfs";
      type = "zfs";
      options = "defaults";
      before = [ "rke2-server.service" ];
      requiredBy = [ "rke2-server.service" ];
    }
  ];

  # Nix substrate config — the declarative form of what rke2lab-nix-install.sh +
  # rke2lab-flox-install.sh wrote into /etc/nix/{nix,flox}.conf on the Debian node.
  # NOT translated here (handled elsewhere on purpose):
  #   - access-tokens (github.com=<secret>): a secret → injected per-node via sops-nix, never baked.
  #   - extra-platforms=aarch64-darwin + the hardcoded qemu-x86_64-binfmt sandbox path: a Mac /
  #     emulation artifact (that store path won't exist on NixOS). x86_64 emulation, if wanted,
  #     is boot.binfmt.emulatedSystems — but in a container binfmt comes from the host kernel.
  nix.settings = {
    experimental-features = [
      "nix-command"
      "flakes"
      "ca-derivations"
    ];
    substituters = [
      "https://aseipp-nix-cache.freetls.fastly.net"
      "https://nxmatic.cachix.org"
      "https://cache.nixos.org/"
    ];
    trusted-public-keys = [
      "cache.nixos.org-1:6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY="
      "nxmatic.cachix.org-1:huMghYiwDpPa1PMXHXK4G1Dp4QOZjgsNqxcjf/AjuJ0="
    ];
    # flox public store (mirrors the family's flox.conf).
    extra-trusted-substituters = [ "https://cache.flox.dev" ];
    extra-trusted-public-keys = [
      "flox-cache-public-1:7F4OyH7ZCnFhcze3fJdfyXYLQw/aV7GEed86nQ7IsOs="
      "floxhub-1:0QOAlcobcEvq1mqEf4qAYCaWnTTOXpyoRv/PmqfSixM="
    ];
    trusted-users = [
      "root"
      "@wheel"
    ];
    sandbox = false;
    accept-flake-config = true;
    # flox.conf tuning: fail fast on flaky Fastly routing to cache.nixos.org; GC thresholds.
    connect-timeout = 10;
    stalled-download-timeout = 30;
    min-free = 128000000;
    max-free = 1000000000;
  };

  # The node toolbox, baked on PATH (option B): the union of what the former nocloud + rke2
  # flox envs installed (yq-go/dasel/git/gh + kubectl/helm), now plain systemPackages. On NixOS
  # the closure IS the env — no `flox activate` to put yq on PATH. The two flox envs collapse
  # into this; flox itself is kept ONLY for its irreplaceable role: the containerd NRI workload
  # runtime (flox-nri-plugin, next iteration).
  #
  # What the flox profiles ALSO carried, and where it goes instead (NOT here):
  #   - /srv/host RKE2LAB_* path vars  -> dissolve with the /srv/host delivery mechanism itself.
  #   - RKE2LAB_NODE_{ID,NAME,KIND}    -> per-node identity, injected via a generated EnvironmentFile.
  #   - .secrets tokens (flox/cachix)  -> sops-nix (CACHIX_AUTH_TOKEN et al. as secret env vars).
  environment.systemPackages = [
    flox.packages.${pkgs.stdenv.hostPlatform.system}.default
    pkgs.yq-go
    pkgs.dasel
    pkgs.git
    pkgs.gh
    pkgs.kubectl
    pkgs.kubernetes-helm
  ];
  environment.shellAliases.k = "KUBECONFIG=/etc/rancher/rke2/rke2.yaml kubectl";
}
