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
    ];
    config = {
      Cmd = [ "/bin/sh" ];
      # nix-built tools find the CA bundle via SSL_CERT_FILE (real file below).
      Env = [
        "SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
        "NIX_SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
      ];
    };
    # /etc as REAL files, NOT store symlinks. `flox activate` does a getpwuid(0) at
    # startup → needs /etc/passwd, or it dies "ENOENT" before doing anything (proven
    # by strace). dockerTools.fakeNss / caCertificates would supply these — but as
    # SYMLINKS into /nix/store, and the flox NRI plugin overlays /nix/store with the
    # HOST store, which SHADOWS the image's own store paths → those symlinks DANGLE
    # in the container (cat /etc/passwd → ENOENT under the overlay). busybox shipped
    # these as real files; write them the same way so they survive the overlay. The
    # CA bundle is copied (content), not symlinked, for workloads' external HTTPS
    # (e.g. tailscale-client `tailscale up`).
    extraCommands = ''
      mkdir -p etc etc/ssl/certs
      printf 'root:x:0:0:root:/root:/bin/sh\nnobody:x:65534:65534:nobody:/nonexistent:/bin/sh\n' > etc/passwd
      printf 'root:x:0:\nnogroup:x:65534:\n' > etc/group
      printf 'passwd: files\ngroup: files\nhosts: files dns\n' > etc/nsswitch.conf
      cp ${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt etc/ssl/certs/ca-bundle.crt
      # A world-writable /tmp: dockerTools images ship none, but injected scripts
      # (e.g. headplane agent-sync's `yq … > /tmp/config.$$.yaml`) expect it, and
      # the NRI overlay only covers /nix/store so it won't provide one either.
      mkdir -p tmp
      chmod 1777 tmp
    '';
  };
in
{
  systemd.tmpfiles.rules = [
    "L+ /var/lib/rancher/rke2/agent/images/flox-carrier.tar.gz - - - - ${carrier}"
  ];
}
