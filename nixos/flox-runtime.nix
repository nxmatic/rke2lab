# DRAFT (approach (a), NOT yet wired into nixos/default.nix) — for review.
#
# Bake the flox ENVS (store-resolved) + their GC-roots + the 3 OCI hooks +
# /etc/flox.toml into the node-base image, so the foundation NRI plugin
# (nixos/containerd.nix) resolves envs WITHOUT any boot-time `flox activate`.
# Image-build (declarative) replacement for the old boot installer
# (prebuild_runtime_packages → flox activate → gcroot_env_packages).
#
# ── Mechanism validated by spike (2026-08-25), TWO artifacts per env ──────────
#  1. env SUBTREE  = a store path with env/{manifest.toml,manifest.lock}. This is
#     what the NRI plugin resolveFloxEnvironment reads (stats <path>/env) and the
#     env-link hook symlinks (.flox/env -> <path>/env). TRIVIAL: copy the raw
#     manifest.toml + the committed manifest.lock into $out/env/. This is what we
#     GC-root at /nix/var/nix/gcroots/flox-runtime/env/<category>/<name>.
#  2. ACTIVATION   = flox's own `buildenv.nix` output (-dev/-run) + the package
#     closure (kdns-debug + catalog). Baked so the container's `flox activate` is
#     a pure cache-hit (no runtime build). Building it realises the closure.
#
# ── IMPURITY + REALISE (proven) ───────────────────────────────────────────────
# buildenv.nix does builtins.storePath on the lock's REALISED outputs → forbidden
# in pure eval AND requires the outputs present. The build flow therefore:
#   (i)  realises each env's closure first — catalog pkgs substitute from
#        cache.nixos.org; kdns-debug (no substituter) BUILDS on the aarch64-linux
#        builder (bioskop-nixos), yielding exactly the lock's pinned path;
#   (ii) evals/builds with the flake's `nixConfig.pure-eval = false` honoured via
#        `--accept-flake-config`, and `--system aarch64-linux` so buildenv.nix's
#        `builtins.currentSystem` keys correctly.
# Both flags go on the node-base `nix build` in build-node-base-image.sh (which
# today omits them because the eval was pure — that comment becomes stale).
{
  pkgs,
  flox,
  lib,
  ...
}:
let
  system = pkgs.stdenv.hostPlatform.system; # aarch64-linux on the node image

  buildenvNix = "${flox.packages.${system}.flox-buildenv}/lib/buildenv.nix";

  # Env catalog source (still under manifests-core/resources for now; a later
  # de-burial move relocates runtime/flox out of src/main/resources).
  envCatalog =
    ../osgi/domains/manifests/manifests-core/src/main/resources/runtime/flox/environment.d;

  # Artifact 1 — the static env subtree the plugin GC-roots + the hook symlinks.
  mkEnvSubtree = category: name:
    pkgs.runCommandLocal "flox-env-${category}-${name}-subtree" { } ''
      mkdir -p "$out/env"
      cp ${envCatalog}/${category}/${name}/manifest.toml "$out/env/manifest.toml"
      cp ${envCatalog}/${category}/${name}/manifest.lock "$out/env/manifest.lock"
    '';

  # Artifact 2 — flox's activation package (+ closure), baked for the container's
  # `flox activate` cache-hit. buildenv.nix reads the committed lock.
  mkEnvActivation = category: name:
    import buildenvNix {
      manifestLock = "${envCatalog}/${category}/${name}/manifest.lock";
      name = "flox-env-${category}-${name}";
      varsOrder = builtins.toJSON [ ];
    };

  # The env catalog to bake. Each gets a subtree (GC-rooted) + activation (baked).
  envs = [
    { category = "networking"; name = "kdns"; }
    # add each env here once it has a committed manifest.lock
  ];

  subtreeOf = e: mkEnvSubtree e.category e.name;
  activationOf = e: mkEnvActivation e.category e.name;

  # The 3 OCI hooks the plugin references at fixed /usr/local/sbin paths
  # (sources recovered from 6e8cd3c28^, kept under nixos/flox/hooks/).
  floxHooks = pkgs.runCommandLocal "flox-nri-hooks" { } ''
    mkdir -p $out/sbin
    install -m0755 ${./flox/hooks/flox-nri-overlay-hook.sh}  $out/sbin/flox-nri-overlay-hook.sh
    install -m0755 ${./flox/hooks/flox-nri-env-link-hook.sh} $out/sbin/flox-nri-env-link-hook.sh
    install -m0755 ${./flox/hooks/flox-nri-chown-hook.sh}    $out/sbin/flox-nri-chown-hook.sh
  '';
in
{
  # GC-root each env SUBTREE at the plugin's well-known path (declarative twin of
  # the old `nix-store --add-root --indirect`). The ACTIVATION package rides into
  # the image closure via a second gcroot so it is kept for the container cache-hit.
  systemd.tmpfiles.rules =
    (map
      (e: "L+ /nix/var/nix/gcroots/flox-runtime/env/${e.category}/${e.name} - - - - ${subtreeOf e}")
      envs)
    ++ (map
      (e: "L+ /nix/var/nix/gcroots/flox-runtime/activation/${e.category}/${e.name} - - - - ${activationOf e}")
      envs)
    ++ [
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
