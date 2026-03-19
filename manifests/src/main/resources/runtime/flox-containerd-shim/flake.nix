{
  description = "RKE2 Flox containerd shim helper package";

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
    in {
      packages = {
        flox-shim-wrapper = pkgs.buildGoModule {
          pname = "flox-shim-wrapper";
          version = "0.1.0";
          src = builtins.path {
            path = ./wrapper-go;
            name = "flox-shim-wrapper-src";
          };
          subPackages = ["cmd/containerd-shim-flox-v2"];
          vendorHash = "sha256-g+yaVIx4jxpAQ/+WrGKxhVeliYx7nLQe/zsGpxV4Fn4=";

          env = {
            CGO_ENABLED = "0";
          };

          ldflags = [
            "-s"
            "-w"
          ];

          postInstall = ''
            runHook postInstallPre

            install -D -m 0755 ${./flox-rootfs-sync.sh} \
              "$out/libexec/rke2lab/flox-shim-wrapper/flox-rootfs-sync.sh"

            patchShebangs "$out/libexec/rke2lab/flox-shim-wrapper/flox-rootfs-sync.sh"

            runHook postInstallPost
          '';

          meta = with pkgs.lib; {
            description = "Host-installed wrapper and helper for the Flox-backed containerd shim";
            license = licenses.mit;
            platforms = platforms.unix;
          };
        };
      };

      defaultPackage = self.packages.${system}.flox-shim-wrapper;
    });
}