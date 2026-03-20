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
      lib = pkgs.lib;

      mkFloxShimWrapper = {
        packageName,
        debug ? false,
      }:
        pkgs.buildGoModule {
          pname = packageName;
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

          nativeBuildInputs = lib.optionals debug [pkgs.makeWrapper];

          ldflags =
            lib.optionals (!debug) [
              "-s"
              "-w"
            ];

          gcflags = lib.optionals debug ["all=-N -l"];

          postInstall = ''
            runHook postInstallPre

            install -D -m 0755 ${./flox-rootfs-sync.sh} \
              "$out/libexec/rke2lab/flox-shim-wrapper/flox-rootfs-sync.sh"

            patchShebangs "$out/libexec/rke2lab/flox-shim-wrapper/flox-rootfs-sync.sh"

            runHook postInstallPost
          '';

          postFixup = lib.optionalString debug ''
            wrapProgram "$out/bin/containerd-shim-flox-v2" \
              --prefix PATH : ${lib.makeBinPath [pkgs.delve]}
          '';

          meta = with pkgs.lib; {
            description =
              if debug
              then "Host-installed debug wrapper and helper for the Flox-backed containerd shim"
              else "Host-installed wrapper and helper for the Flox-backed containerd shim";
            license = licenses.mit;
            platforms = platforms.unix;
          };
        };
    in {
      packages = {
        flox-shim-wrapper = mkFloxShimWrapper {
          packageName = "flox-shim-wrapper";
        };

        flox-shim-wrapper-debug = mkFloxShimWrapper {
          packageName = "flox-shim-wrapper-debug";
          debug = true;
        };
      };

      defaultPackage = self.packages.${system}.flox-shim-wrapper;
    });
}