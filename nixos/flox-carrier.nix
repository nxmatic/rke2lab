# The minimal OCI carrier image every flox-injected pod runs as its base.
#
# WHY a nix-built carrier (not busybox:stable / alpine:latest): the flox NRI
# plugin overlays the host /nix/store + the env symlink farm into the container,
# then the pod command is `flox activate -- <cmd>`. The base image only has to
# provide (1) `/usr/bin/env` so portable `#!/usr/bin/env bash` shebangs resolve
# (busybox's env lacked GNU `-S` and forced shebang gymnastics), and (2) a shell
# + coreutils for `flox activate` itself. Everything else — bash, kdns, kubectl,
# delve — comes from the overlaid store on PATH (prod vs debug is a property of
# the flox ENV now, not the base image, so prod + debug share ONE carrier).
#
# WHY build it from nix: (a) removes the docker.io pull (the node is air-gapped);
# (b) pushes nix-store usage down into the container layers.
#
# DELIVERY — no registry: buildLayeredImage yields a store `.tar.gz`; a tmpfiles
# symlink drops it into /var/lib/rancher/rke2/agent/images/ where rke2's air-gap
# path auto-imports it into local containerd (k8s.io namespace) at boot. Pods
# reference it by its exact RepoTag with imagePullPolicy: IfNotPresent — present
# locally ⇒ never pulled. The RepoTag MUST match FloxDebugPolicy's constants
# (io.seedmatic.rke2lab.manifests.contract.profiles.FloxDebugPolicy).
{ pkgs, ... }:
let
  name = "rke2lab/flox-carrier";
  tag = "0.1.0";

  carrier = pkgs.dockerTools.buildLayeredImage {
    inherit name tag;
    contents = [
      pkgs.dockerTools.usrBinEnv # /usr/bin/env → coreutils env (the shebang target)
      pkgs.dockerTools.binSh # /bin/sh → shell for `flox activate`
      pkgs.bashInteractive # /bin/bash
      pkgs.coreutils # env, sleep, … (the debug sidecar's default Cmd)
      # /etc/passwd + /etc/group + /etc/nsswitch.conf. `flox activate` does a
      # getpwuid(0) at startup to resolve the user's home; with no /etc/passwd it
      # fails "ENOENT: No such file or directory" before doing anything (proven by
      # strace). busybox shipped these; a minimal nix image must add them.
      pkgs.dockerTools.fakeNss
      # /etc/ssl/certs CA bundle — not needed by `flox activate` (offline
      # cache-hit), but workloads on this carrier make external HTTPS calls
      # (e.g. tailscale-client's `tailscale up` → controlplane.tailscale.com),
      # which fail "SSL CA cert" without a trust store.
      pkgs.dockerTools.caCertificates
    ];
    config.Cmd = [ "/bin/sh" ];
  };
in
{
  systemd.tmpfiles.rules = [
    "L+ /var/lib/rancher/rke2/agent/images/flox-carrier.tar.gz - - - - ${carrier}"
  ];
}
