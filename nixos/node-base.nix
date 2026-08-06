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
  # Per-node identity is NOT baked: the image is homogeneous. rke2lab-identity.service (below) reads
  # the incus instance's user.rke2lab.node-* keys over devlxd at boot and sets the transient
  # hostname to <cluster>-<node> (so mDNS resolves <cluster>-<node>.local, and rke2 registers the
  # node under it). Empty here means "NixOS manages no hostname" — the service owns it at runtime.
  networking.hostName = lib.mkForce "";

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

  # Per-node identity, resolved at boot from the incus instance's user.rke2lab.node-* config keys
  # over devlxd (/dev/incus/sock — always present in an incus guest). The scion projected these from
  # the netplan blueprint (GrowIdentityView); this service reads them back and (a) writes the shared
  # /run/rke2lab/node.env the zfs mount consumes, (b) sets the transient hostname to <cluster>-<node>
  # so mDNS resolves it and rke2 registers the node under it. Ordered before rke2 and avahi so both
  # see the resolved hostname. No cloud-init, no host file mount — four scalars over the guest API.
  systemd.services.rke2lab-identity = {
    description = "rke2lab node identity (devlxd → /run/rke2lab/node.env + hostname)";
    wantedBy = [ "multi-user.target" ];
    before = [
      "rke2-server.service"
      "rke2lab-zfs-containerd.service"
      "avahi-daemon.service"
    ];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
    };
    path = [ pkgs.curl ];
    script = ''
      set -euo pipefail
      sock=/dev/incus/sock
      get() {
        # devlxd exposes user.* keys at /1.0/config/<key>; the socket is up from container start,
        # but retry briefly to be robust against an early first read.
        for _ in $(seq 1 20); do
          if curl -sf --unix-socket "$sock" "http://x/1.0/config/user.rke2lab.$1"; then
            return 0
          fi
          sleep 0.5
        done
        echo "rke2lab-identity: devlxd key user.rke2lab.$1 unavailable" >&2
        return 1
      }
      name="$(get node-name)"
      hostname="$(get node-hostname)"
      kind="$(get node-kind)"
      id="$(get node-id)"
      install -d -m 0755 /run/rke2lab
      umask 022
      cat >/run/rke2lab/node.env <<EOF
      RKE2LAB_NODE_NAME=$name
      RKE2LAB_NODE_HOSTNAME=$hostname
      RKE2LAB_NODE_KIND=$kind
      RKE2LAB_NODE_ID=$id
      EOF
      # Transient hostname (no dbus/hostnamed dependency at this ordering point) — avahi and rke2,
      # ordered after, read it via gethostname().
      printf '%s' "$hostname" >/proc/sys/kernel/hostname
    '';
  };

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

  # dbus-over-TCP for the seed-master systemd adapter — the baked replacement for the old
  # rke2lab-dbus-tcp-system-bus.sh host script. The adapter opens an anonymous-SASL DBus connection
  # to a node's system bus over TCP (port 12434) to read live unit state. Baked on EVERY node (not
  # master-only as the Debian script was): the substrate is homogeneous, exposing the bus uniformly
  # on the lab bridge is a good-to-have and lets this be FULLY DECLARATIVE — no runtime node.env
  # gate, no dbus restart. Two pieces:
  #   1. the anonymous-allow policy;
  #   2. a dbus.socket drop-in adding the TCP ListenStream beside the unix socket (the leading ""
  #      resets systemd's inherited list so the unix listener survives). dbus comes up with the TCP
  #      listener from boot.
  # Classic dbus-daemon, NOT dbus-broker (the NixOS default): dbus-java's anonymous SASL over TCP
  # relies on <auth>ANONYMOUS</auth> + <allow_anonymous/>, which the broker does not honour.
  services.dbus.implementation = "dbus";
  services.dbus.packages = [
    (pkgs.writeTextDir "share/dbus-1/system.d/40-rke2lab-allow-all.conf" ''
      <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
       "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
      <busconfig>
        <auth>ANONYMOUS</auth>
        <allow_anonymous/>
        <policy context="default">
          <allow send_type="method_call"/>
          <allow send_type="method_return"/>
          <allow send_type="signal"/>
          <allow send_type="error"/>
          <allow send_destination="*"/>
          <allow receive_type="method_call"/>
          <allow receive_type="method_return"/>
          <allow receive_type="signal"/>
          <allow receive_type="error"/>
          <allow eavesdrop="true"/>
          <allow own="*"/>
        </policy>
      </busconfig>
    '')
  ];
  systemd.sockets.dbus = {
    overrideStrategy = "asDropin";
    socketConfig.ListenStream = [
      ""
      "/run/dbus/system_bus_socket"
      "0.0.0.0:12434"
    ];
  };

  # mDNS advertisement — the seed-master systemd adapter (and the incus remote) reach this node by
  # its <cluster>-<node>.local name, so the node must ANSWER that name over mDNS. rke2lab-identity
  # sets the hostname at runtime (ordered before avahi below), and avahi publishes it + its LAN
  # addresses. Without this the guest never advertises: nikopol-master.local is unresolvable and the
  # adapter probe dies with UnknownHostException before it ever reaches dbus :12434 (the host
  # nikopol-nixos.local resolves only because the HOST runs avahi; the guest must run its own).
  services.avahi = {
    enable = true;
    ipv4 = true;
    ipv6 = true;
    publish = {
      enable = true;
      addresses = true;
      workstation = true;
    };
  };

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
    # zfs userspace: the containerd zfs snapshotter shells `zfs`/`zpool` for its per-container
    # datasets, and rke2lab-zfs-containerd.service above needs `mount.zfs` to mount the legacy
    # dataset (it also carries pkgs.zfs on its own unit PATH). The kernel module comes from the host
    # (this is an incus container with /dev/zfs passed in) — only the userspace tools belong here.
    pkgs.zfs
  ];
  environment.shellAliases.k = "KUBECONFIG=/etc/rancher/rke2/rke2.yaml kubectl";
}
