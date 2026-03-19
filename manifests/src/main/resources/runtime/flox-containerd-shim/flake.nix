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
        flox-shim-wrapper = pkgs.stdenvNoCC.mkDerivation {
          pname = "flox-shim-wrapper";
          version = "0.1.0";
          src = ./.;

          dontConfigure = true;
          dontBuild = true;

          installPhase = ''
            runHook preInstall

            install -D -m 0755 "$src/containerd-shim-flox-v2-wrapper.sh" \
              "$out/bin/containerd-shim-flox-v2"
            install -D -m 0755 "$src/flox-rootfs-sync.sh" \
              "$out/libexec/rke2lab/flox-shim-wrapper/flox-rootfs-sync.sh"

            patchShebangs \
              "$out/bin/containerd-shim-flox-v2" \
              "$out/libexec/rke2lab/flox-shim-wrapper/flox-rootfs-sync.sh"

            runHook postInstall
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