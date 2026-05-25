{
  description = "Flox runtime: NRI plugin + per-workload packages (kdns, headplane, ...) used by the rke2lab cluster";

  inputs = {
    flake-commons.url = "github:nxmatic/nix-flake-commons/develop";
    nixpkgs.follows = "flake-commons/nixpkgs";
    flake-utils.follows = "flake-commons/flake-utils";

    # Per-workload sources. Adding a new workload package usually means a new
    # input + a new entry in `packages` below; the per-env manifest.toml then
    # references it via `flake = path:.../runtime/flox#<output>`.
    kdns-src = {
      url = "github:lab42/kdns?ref=v0.2.15";
      flake = false;
    };
    headplane = {
      url = "github:tale/headplane?ref=v0.6.3";
      inputs.flake-utils.follows = "flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
    kdns-src,
    headplane,
    ...
  }:
    flake-utils.lib.eachSystem [
      "aarch64-darwin"
      "aarch64-linux"
    ] (system: let
      pkgs = import nixpkgs {
        inherit system;
        overlays = [self.overlays.default];
      };
      lib = pkgs.lib;

      # ---- NRI plugin -------------------------------------------------
      nriPluginCommon = {
        pname = "flox-nri-plugin";
        src = builtins.path {
          path = ./nri-plugin;
          name = "nri-plugin-src";
        };
        subPackages = ["cmd/flox-nri-plugin"];
        vendorHash = "sha256-f2tBtvXqS4XfkKsNQJnwxobVNRFCwEusKC4Et7rySEk="; # lib.fakeHash;
        env.CGO_ENABLED = "0";
        tags = ["netgo"];
        meta = with lib; {
          description = "Flox NRI plugin for containerd - injects flox environments into containers";
          license = licenses.mit;
          platforms = platforms.unix;
        };
      };

      nriPluginVersion = "0.1.7";

      flox-nri-plugin = pkgs.buildGoModule (nriPluginCommon
        // {
          version = nriPluginVersion;
          ldflags = [
            "-s"
            "-w"
            "-X main.pluginVersion=${nriPluginVersion}"
          ];
        });

      flox-nri-plugin-debug = pkgs.buildGoModule (nriPluginCommon
        // {
          pname = "flox-nri-plugin-debug";
          version = nriPluginVersion;
          dontStrip = true;
          buildFlagsArray = ["-gcflags=all=-N -l"];
          ldflags = ["-X main.pluginVersion=${nriPluginVersion}-debug"];
        });

      # ---- kdns -------------------------------------------------------
      mkKdns = {
        packageName,
        debug,
      }:
        pkgs.buildGoModule rec {
          pname = packageName;
          version = "0.2.15";

          src = kdns-src;

          vendorHash = "sha256-pPGuBNI/qcGr3EgVQMa6Xw0PRA4iUGMLDnw4nCWqJ3U=";

          env.CGO_ENABLED = "0";

          nativeBuildInputs = lib.optionals debug [pkgs.makeWrapper];

          ldflags =
            lib.optionals (!debug) [
              "-s"
              "-w"
            ]
            ++ [
              "-extldflags=-static"
              "-X github.com/lab42/kdns/cmd.Version=${version}"
              "-X github.com/lab42/kdns/cmd.Commit=${src.rev or "dev"}"
              "-X github.com/lab42/kdns/cmd.Date=1970-01-01T00:00:00Z"
            ];

          gcflags = lib.optionals debug ["all=-N -l"];

          postFixup = lib.optionalString debug ''
            wrapProgram "$out/bin/kdns" \
              --prefix PATH : ${lib.makeBinPath [pkgs.delve]}
          '';

          meta = with lib; {
            description =
              if debug
              then "Kubernetes DNS controller with mDNS support (debug build)"
              else "Kubernetes DNS controller with mDNS support";
            homepage = "https://github.com/lab42/kdns";
            license = licenses.mit;
            platforms = platforms.unix;
          };
        };

      kdns = mkKdns {
        packageName = "kdns";
        debug = false;
      };

      kdns-debug = mkKdns {
        packageName = "kdns-debug";
        debug = true;
      };
    in {
      packages = {
        inherit flox-nri-plugin flox-nri-plugin-debug;
        inherit kdns kdns-debug;

        # headplane outputs flow through the overlay defined below so the
        # darwin pnpm-deps override is in scope. The overlay is shared with
        # any consumer that imports the runtime flake's overlays.default.
        inherit (pkgs) headplane headplane-agent headplane-nixos-docs headplane-ssh-wasm headscale;

        default = flox-nri-plugin;
      };

      defaultPackage = flox-nri-plugin;
    })
    // {
      # Cross-system overlay so headplane builds on darwin (pnpm hash override)
      # and aarch64-linux alike. Mirrors the upstream headplane overlay with a
      # single pnpm-deps fix-up for darwin.
      overlays.default = final: prev: let
        upstream = headplane.overlays.default final prev;
      in
        upstream
        // {
          headplane =
            if final.stdenv.hostPlatform.isDarwin
            then
              upstream.headplane.overrideAttrs (old: {
                pnpmDeps = final.pnpm_10.fetchDeps {
                  inherit (old) pname version src;
                  hash = "sha256-oSlxe//0AUA9oIFA6piULkHcDnbc+MMVvfMcah9IoxM=";
                  fetcherVersion = 1;
                };
              })
            else upstream.headplane;
        };
    };
}
