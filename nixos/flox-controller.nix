# The flox-controller node-agent image, baked into the node substrate.
#
# WHY baked (foundation): the controller is the companion that PROVISIONS what the
# flox NRI plugin injects — it reconciles FloxEnv CRs onto the node's /nix store
# (closures + GC-roots) and, for `consumption: image` envs, `flox containerize`s +
# imports the carriers into containerd. Chicken-and-egg: the controller's OWN image
# must exist before it can produce any carrier, so it is baked here (not produced by
# itself). Every OTHER flox image — the carriers — comes FROM the controller at
# runtime, not from a bake (see docs at github:seedmatic/flox-controller).
#
# DELIVERY — no registry (same air-gap path as flox-carrier / the NRI plugin): the
# flox-controller flake's buildLayeredImage yields a store `.tar.gz`; a tmpfiles
# symlink drops it into /var/lib/rancher/rke2/agent/images/ where rke2 auto-imports
# it into local containerd (k8s.io namespace) at boot. The FloxControllerManifestsUnit
# DaemonSet references it by RepoTag with imagePullPolicy: IfNotPresent — present
# locally ⇒ never pulled. The RepoTag MUST match FloxDebugPolicy#floxControllerImage
# (io.seedmatic.flox-controller:<VERSION>).
{ pkgs, flox-controller, ... }:
let
  image = flox-controller.packages.${pkgs.stdenv.hostPlatform.system}.flox-controller-image;
in
{
  systemd.tmpfiles.rules = [
    "L+ /var/lib/rancher/rke2/agent/images/flox-controller.tar.gz - - - - ${image}"
  ];
}
