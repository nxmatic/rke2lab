# Bake the flox NRI plugin's 3 OCI hooks + /etc/flox.toml into the node-base image.
#
# The flox NRI plugin binary is a baked systemd service (nixos/containerd.nix); the
# workload ENVS are no longer baked here — they are delivered at runtime as FloxEnv CRs
# the flox-controller realises onto the node's /nix/store (see FloxEnvManifestsUnit /
# docs/architecture/patterns/flox-store-resolved-runtime-and-builder.adoc). This file
# used to also bake environment.d envs + their GC-roots (flox buildenv.nix, per-env
# subtree/activation/closure GC-roots); that machinery was retired with the FloxEnv-CR
# migration — only the hooks + host-wide flox policy remain baked.
{
  pkgs,
  flox-runtime,
  ...
}:
let
  system = pkgs.stdenv.hostPlatform.system; # aarch64-linux on the node image

  # The 3 OCI hooks the plugin references at fixed /usr/local/sbin paths ship from the
  # flox-nri-plugin fork (the shim owns its binary + hooks + their contract as one unit)
  # as the `flox-nri-hooks` package — patchShebangs'd, PATH-wrapped, with a static `mount`
  # for the overlay hook's pre-pivot chroot. See github:seedmatic/flox-nri-plugin.
  floxHooks = flox-runtime.packages.${system}.flox-nri-hooks;
in
{
  systemd.tmpfiles.rules = [
    "L+ /usr/local/sbin/flox-nri-overlay-hook.sh  - - - - ${floxHooks}/sbin/flox-nri-overlay-hook.sh"
    "L+ /usr/local/sbin/flox-nri-env-link-hook.sh - - - - ${floxHooks}/sbin/flox-nri-env-link-hook.sh"
    "L+ /usr/local/sbin/flox-nri-chown-hook.sh    - - - - ${floxHooks}/sbin/flox-nri-chown-hook.sh"
  ];

  # Host-wide flox policy the plugin bind-mounts read-only into every injected
  # container (telemetry off, channel lock, ...).
  environment.etc."flox.toml".text = ''
    [options]
    # telemetry off + any host-wide flox policy
  '';
}
