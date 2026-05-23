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

      nriPlugin = pkgs.buildGoModule {
        pname = "flox-nri-plugin";
        version = "0.1.0";
        src = builtins.path {
          path = ./wrapper-go;
          name = "wrapper-go-src";
        };
        subPackages = ["cmd/flox-nri-plugin"];
        vendorHash = "sha256-KnrEpSTBwgX7/C3PozIh6dL86DJmi6+0W4gYaHYKZyo=";

        env = {
          CGO_ENABLED = "0";
        };

        ldflags = [
          "-s"
          "-w"
        ];

        meta = with pkgs.lib; {
          description = "Flox NRI plugin for containerd - injects flox environments into containers";
          license = licenses.mit;
          platforms = platforms.unix;
        };
      };
    in {
      packages = {
        flox-nri-plugin = nriPlugin;
      };

      defaultPackage = self.packages.${system}.flox-nri-plugin;
    });
}