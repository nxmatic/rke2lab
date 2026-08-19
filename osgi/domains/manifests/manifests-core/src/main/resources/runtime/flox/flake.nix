{
  description = "Flox runtime: NRI plugin + per-workload packages (kdns, headplane, ...) used by the rke2lab cluster";

  inputs = {
    flake-commons.url = "github:seedmatic/nix-flake-commons/develop";
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
    # Headplane source ref kept separately from the upstream flake input so we
    # can re-derive `headplane` here with debug symbols + sourcemaps preserved
    # without losing the upstream cross-system overlay (which fixes the darwin
    # pnpm-deps hash). Prod stays on `pkgs.headplane` via the overlay; this
    # re-derivation only matters when an operator activates the debug env.
    headplane-src = {
      url = "github:tale/headplane?ref=v0.6.3";
      flake = false;
    };
    # Upstream headscale flake, pinned to v0.28.0 which builds with Go 1.25.5
    # (compatible with nixpkgs 1.25.9). Upstream main bumped to Go 1.26 which
    # nixpkgs can't satisfy yet — bump this tag once nixpkgs catches up.
    headscale = {
      url = "github:juanfont/headscale?ref=v0.28.0";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
    kdns-src,
    headplane,
    headplane-src,
    headscale,
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
      # `doCheck = false`: this is a lab-only build, the upstream tests add
      # build time without catching anything we care about for the rke2lab use
      # case. Same rationale applies to every workload below.
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
        doCheck = false;
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

          doCheck = false;

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

      # ---- headscale --------------------------------------------------
      # Sourced from the upstream juanfont/headscale flake — they pin the Go
      # toolchain themselves (main is on Go 1.26 which nixpkgs 1.25.9 can't
      # satisfy). Two outputs:
      #   - prod: upstream as-is (stripped, smallest binary, normal latency)
      #   - debug: overrideAttrs to drop `-s -w`, disable strip, build with
      #     `-N -l` (no inlining/optimization), wrap with delve in PATH so the
      #     shell sidecar can `dlv attach $(pgrep headscale)` with full
      #     source-level visibility through the shared PID namespace.
      headscale-prod =
        (headscale.packages.${system}.headscale or headscale.packages.${system}.default)
        .overrideAttrs (_: {
          doCheck = false;
        });

      headscale-debug = headscale-prod.overrideAttrs (old: {
        pname = "headscale-debug";
        dontStrip = true;
        ldflags = lib.filter (f: f != "-s" && f != "-w") (old.ldflags or []);
        gcflags = (old.gcflags or []) ++ ["all=-N -l"];
        nativeBuildInputs = (old.nativeBuildInputs or []) ++ [pkgs.makeWrapper];
        postFixup =
          (old.postFixup or "")
          + ''
            wrapProgram "$out/bin/headscale" \
              --prefix PATH : ${lib.makeBinPath [pkgs.delve]}
          '';
      });

      # ---- tailscale --------------------------------------------------
      # Same pattern as headscale: upstream prod from nixpkgs (override flips
      # checkPhase off), debug overrideAttrs to drop strip flags + add delve
      # wrapping. Both `tailscale` and `tailscaled` get wrapped so the
      # operator can debug either side.
      #
      # `doCheck = false`: lab-only build (same rationale as the others).
      # Tailscale's atomicfile_test specifically fails inside the nix sandbox
      # because the build tmpdir path can exceed the unix-socket name limit
      # (TestDoesNotOverwriteIrregularFiles).
      tailscale-prod = pkgs.tailscale.overrideAttrs (_: {
        doCheck = false;
      });

      tailscale-debug = tailscale-prod.overrideAttrs (old: {
        pname = "tailscale-debug";
        dontStrip = true;
        ldflags = lib.filter (f: f != "-s" && f != "-w") (old.ldflags or []);
        gcflags = (old.gcflags or []) ++ ["all=-N -l"];
        nativeBuildInputs = (old.nativeBuildInputs or []) ++ [pkgs.makeWrapper];
        postFixup =
          (old.postFixup or "")
          + ''
            for bin in tailscale tailscaled; do
              wrapProgram "$out/bin/$bin" \
                --prefix PATH : ${lib.makeBinPath [pkgs.delve]}
            done
          '';
      });

      # ---- headplane (debug only) -------------------------------------
      # Prod headplane stays on `pkgs.headplane` via the cross-system overlay
      # below — that overlay carries the darwin pnpm-deps hash override the
      # operator runs into when `flox lock` evaluates the env on darwin. For
      # debug we re-derive from `headplane-src` with sourcemaps preserved so
      # `node --inspect` resolves to TS lines, accepting that we manage the
      # pnpm-deps hash ourselves for this single derivation.
      headplane-debug = pkgs.stdenv.mkDerivation rec {
        pname = "headplane-debug";
        version = "0.6.3";
        src = headplane-src;

        nativeBuildInputs = [pkgs.nodejs_22 pkgs.pnpm_10 pkgs.pnpm_10.configHook];
        buildInputs = [pkgs.nodejs_22];

        # First-build placeholder (same workflow as the vendorHash pattern):
        # run `nix build .#headplane-debug` and copy the printed SRI hash here.
        pnpmDeps = pkgs.pnpm_10.fetchDeps {
          inherit pname version src;
          hash = "sha256-Xtooqpibv4fuJczUfJDlGt2+5KuoKq/TUUhLKE+ierA="; # lib.fakeHash;
          fetcherVersion = 1;
        };

        # Skip minification + keep sourcemaps so node --inspect lands on
        # readable TS lines instead of mangled output.
        env.NODE_ENV = "development";

        buildPhase = ''
          runHook preBuild
          pnpm run build
          runHook postBuild
        '';

        installPhase = ''
          runHook preInstall
          mkdir -p $out/share/headplane
          cp -r build node_modules package.json $out/share/headplane/
          mkdir -p $out/bin
          cat > $out/bin/headplane <<EOF
          #!${pkgs.runtimeShell}
          exec ${pkgs.nodejs_22}/bin/node --enable-source-maps --inspect=0.0.0.0:9229 \
            $out/share/headplane/build/server/index.js "\$@"
          EOF
          chmod +x $out/bin/headplane
          runHook postInstall
        '';

        meta = with lib; {
          description = "Headplane web UI (debug build with sourcemaps + node --inspect)";
          homepage = "https://github.com/tale/headplane";
          license = licenses.agpl3Only;
          platforms = platforms.unix;
        };
      };
    in {
      packages = {
        inherit flox-nri-plugin flox-nri-plugin-debug;
        inherit kdns kdns-debug;
        inherit headplane-debug;

        # Prod = upstream stripped build; debug = unstripped + `-N -l` + delve
        # wrapper. The Java side (FloxDebugPolicy.resolveFloxEnvironment) flips
        # the prod container's flox env to the `*-debug` env when debug is on,
        # which causes the NRI plugin to mount the debug-package binary in
        # place of the prod one — so port mappings and pod identity stay
        # untouched.
        headscale = headscale-prod;
        tailscale = tailscale-prod;
        inherit headscale-debug tailscale-debug;

        # Prod headplane outputs flow through the overlay defined below so the
        # darwin pnpm-deps override is in scope. The overlay is shared with
        # any consumer that imports the runtime flake's overlays.default. Debug
        # is re-derived above from `headplane-src` so we can preserve sourcemaps
        # for `node --inspect`.
        inherit (pkgs) headplane headplane-agent headplane-nixos-docs headplane-ssh-wasm;

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
