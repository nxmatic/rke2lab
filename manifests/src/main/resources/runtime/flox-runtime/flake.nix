{
  description = "RKE2 Flox NRI plugin for containerd";

  inputs = {
    flake-commons.url = "github:nxmatic/nix-flake-commons/develop";
    nixpkgs.follows = "flake-commons/nixpkgs";
    flake-utils.follows = "flake-commons/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
    ...
  }:
    flake-utils.lib.eachSystem [
      "aarch64-darwin"
      "aarch64-linux"
    ] (system: let
      pkgs = import nixpkgs {inherit system;};
      lib = pkgs.lib;

      # Common build attributes
      commonAttrs = {
        pname = "flox-nri-plugin";
        src = builtins.path {
          path = ./nri-plugin;
          name = "nri-plugin-src";
        };
        subPackages = ["cmd/flox-nri-plugin"];
        vendorHash = "sha256-Ule4xfyW6PKTfVRPxBAZoyqp5mkvL/FKC5I/qJ8fWaY="; # lib.fakeHash;

        env = {
          CGO_ENABLED = "0";
        };

        tags = ["netgo"];O

        meta = with pkgs.lib; {
          description = "Flox NRI plugin for containerd - injects flox environments into containers";
          license = licenses.mit;
          platforms = platforms.unix;
        };
      };

      # Version is the single source of truth
      version = "0.1.6";

      # Production build: optimized, stripped
      nriPlugin = pkgs.buildGoModule (commonAttrs // {
        inherit version;
        ldflags = [
          "-s"  # strip symbol table
          "-w"  # strip DWARF debug info
          "-X main.pluginVersion=${version}"
        ];
      });

      # Debug build: unoptimized, with debug symbols
      nriPluginDebug = pkgs.buildGoModule (commonAttrs // {
        pname = "flox-nri-plugin-debug";
        inherit version;
        dontStrip = true;

        buildFlagsArray = [
          "-gcflags=all=-N -l"  # disable optimizations and inlining
        ];

        ldflags = [
          "-X main.pluginVersion=${version}-debug"
        ];
      });
    in {
      packages = {
        flox-nri-plugin = nriPlugin;
        flox-nri-plugin-debug = nriPluginDebug;
        default = nriPlugin;
      };

      defaultPackage = self.packages.${system}.flox-nri-plugin;
    });
}
