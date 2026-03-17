{
  description = "kdns - Kubernetes DNS with mDNS support";

  inputs = {
    flake-commons.url = "github:nxmatic/nix-flake-commons/develop";
    nixpkgs.follows = "flake-commons/nixpkgs";
    flake-utils.follows = "flake-commons/flake-utils";
    kdns-src = {
      url = "github:lab42/kdns?ref=v0.2.15";
      flake = false;
    };
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
    kdns-src,
    ...
  }:
    flake-utils.lib.eachSystem [
      "aarch64-darwin"
      "aarch64-linux"
    ] (system: let
      pkgs = import nixpkgs {inherit system;};
    in {
      packages = {
        kdns = pkgs.buildGoModule rec {
          pname = "kdns";
          version = "0.2.15";

          src = kdns-src;

          vendorHash = "sha256-pPGuBNI/qcGr3EgVQMa6Xw0PRA4iUGMLDnw4nCWqJ3U=";

          env = {
            CGO_ENABLED = "0";
          };

          ldflags = [
            "-s"
            "-w"
            "-extldflags=-static"
            "-X github.com/lab42/kdns/cmd.Version=${version}"
            "-X github.com/lab42/kdns/cmd.Commit=${src.rev or "dev"}"
            "-X github.com/lab42/kdns/cmd.Date=1970-01-01T00:00:00Z"
          ];

          meta = with pkgs.lib; {
            description = "Kubernetes DNS controller with mDNS support";
            homepage = "https://github.com/lab42/kdns";
            license = licenses.mit;
            platforms = platforms.unix;
          };
        };
      };

      defaultPackage = self.packages.${system}.kdns;
    });
}