# rke2lab node-base — the homogeneous NixOS substrate every RKE2 node shares.
# Role (server/agent) and per-node identity (node-ip, hostname, token) are layered on top;
# this module is what is common to all nodes. See docs/architecture/nixos-substrate/target-vision.adoc.
#
# Iteration 1 (path B): stock rke2 server, dual-stack, flox baked. Proven end-to-end by the spike
# (image builds, boots in Incus, rke2-server + containerd come up). Next iterations add the
# flox-nri-plugin (containerd NRI, from the runtime/flox sub-flake), cilium, and the zfs snapshotter.
{
  lib,
  pkgs,
  flox,
  ...
}:
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

  # flox baked as a nix derivation — this is what makes node-base the flox runtime host,
  # replacing the Debian-era `rke2lab-flox-install.sh` curl|sh. The flox-nri-plugin that wires
  # it into rke2's containerd (NRI) lands in the next iteration via the runtime/flox sub-flake.
  environment.systemPackages = [
    flox.packages.${pkgs.stdenv.hostPlatform.system}.default
    pkgs.kubectl
  ];
  environment.shellAliases.k = "KUBECONFIG=/etc/rancher/rke2/rke2.yaml kubectl";
}
