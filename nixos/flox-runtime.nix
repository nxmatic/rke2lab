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
  flox-runtime,
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
  # `flox activate` cache-hit. buildenv.nix content-addresses the lock (`builtins.path`
  # + `readFile`), so the SOURCE path of manifestLock is irrelevant to the resulting
  # environment.drv — only its content is (catalog and subtree copies are identical).
  # What DID matter: we must NOT pass a custom `name`. buildenv defaults `name` to
  # "environment"; a custom name yields a DIFFERENT environment.drv than the one
  # `flox activate` recomputes at runtime (it passes no name), so flox misses the
  # cache and rebuilds it — which needs a nixbld build group the container lacks.
  # Dropping `name` makes the baked drv identical to flox's → cache-hit, no build.
  mkEnvActivation = category: name:
    import buildenvNix {
      manifestLock = "${envCatalog}/${category}/${name}/manifest.lock";
      varsOrder = builtins.toJSON [ ];
    };

  # The env catalog to bake, DERIVED from the filesystem: every
  # environment.d/<category>/<name>/ carrying a committed manifest.lock is baked
  # (subtree GC-root + activation prod + debug). Same single source of truth as the
  # build script's realise loop — drop an env dir + pin it with `lock-envs.sh` and
  # it bakes with no code edit here. An unlocked manifest.toml is skipped (it can't
  # be built reproducibly until `lock-envs.sh` pins it).
  envs =
    let
      dirsIn = p:
        builtins.attrNames (lib.filterAttrs (_: t: t == "directory") (builtins.readDir p));
    in
    lib.concatMap
      (category:
        let catDir = envCatalog + "/${category}";
        in map (name: { inherit category name; })
          (builtins.filter
            (name: builtins.pathExists (catDir + "/${name}/manifest.lock"))
            (dirsIn catDir)))
      (dirsIn envCatalog);

  subtreeOf = e: mkEnvSubtree e.category e.name;

  # Every output store-path of each FLAKE-install package in an env lock. flox's
  # buildenv.nix `builtins.storePath`s EVERY output the lock pins, so the
  # container's `flox activate` cache-hit needs each one present — but the baked
  # activation drv only makes a package's DEFAULT output a runtime dep, so a
  # NON-DEFAULT flake output (e.g. tailscale ships `out` + `derper`) is
  # storePath-checked at build yet never rides into the squashfs. In the
  # container it is absent, `storePath` fails, and flox falls back to realising
  # the flake at its (build-host-ephemeral) `path:…` url → crash. Force the full
  # locked flake closure into the image so activation is a true cache-hit.
  #
  # ONLY flake installs (those carrying a `locked-url`) — the build's `#pkg^*`
  # realise loop guarantees their outputs are present at THIS eval, so
  # `storePath` is safe. Catalog packages (bash, kubectl, …) are deliberately
  # NOT forced: their outputs live on the node's own /nix/store via ordinary
  # substitution, and their non-default outputs aren't pre-realised here (forcing
  # them would fail this eval).
  flakeOutputs = category: name:
    let
      lock = builtins.fromJSON (builtins.readFile "${envCatalog}/${category}/${name}/manifest.lock");
      forImage = builtins.filter
        (p: (p.system or system) == system && (p ? "locked-url"))
        (lock.packages or [ ]);
    in
    builtins.concatLists (map (p: builtins.attrValues (p.outputs or { })) forImage);

  # The 3 OCI hooks the plugin references at fixed /usr/local/sbin paths now ship
  # from the flox-nri-plugin fork (the shim owns its binary + hooks + their
  # contract as one unit) as the `flox-nri-hooks` package — patchShebangs'd,
  # PATH-wrapped, with a static `mount` for the overlay hook's pre-pivot chroot.
  # Here we only PLACE it on the node (tmpfiles below). See
  # github:seedmatic/flox-nri-plugin.
  floxHooks = flox-runtime.packages.${system}.flox-nri-hooks;
in
{
  # GC-root each env SUBTREE at the plugin's well-known path (declarative twin of
  # the old `nix-store --add-root --indirect`). The ACTIVATION package rides into
  # the image closure via a second gcroot so it is kept for the container cache-hit.
  systemd.tmpfiles.rules =
    (map
      (e: "L+ /nix/var/nix/gcroots/flox-runtime/env/${e.category}/${e.name} - - - - ${subtreeOf e}")
      envs)
    # The activation derivation has TWO outputs (`run` + `dev`); `flox activate`
    # runs in dev mode by default and realises the `dev` output. Root EVERY output
    # (not just the default `run`), or flox misses the cache on `dev` and rebuilds
    # the env at runtime (which the container can't do). One GC-root per output
    # keeps them all in the image closure.
    ++ (lib.concatMap
      (e:
        let act = mkEnvActivation e.category e.name;
        in map
          (out: "L+ /nix/var/nix/gcroots/flox-runtime/activation/${e.category}/${e.name}/${out} - - - - ${act.${out}}")
          act.outputs)
      envs)
    # Force every flake-install output (incl. non-default ones like tailscale's
    # `derper`) into the image closure — the symlink target IS the store path, so
    # the squashfs carries it and the container's activation storePath resolves.
    ++ (lib.concatMap
      (e:
        map
          (sp: "L+ /nix/var/nix/gcroots/flox-runtime/closure/${e.category}/${e.name}/${baseNameOf sp} - - - - ${builtins.storePath sp}")
          (flakeOutputs e.category e.name))
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
