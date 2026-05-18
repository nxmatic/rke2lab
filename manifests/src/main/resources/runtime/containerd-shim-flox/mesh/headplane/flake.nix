{
  description = "headplane overlay with aarch64-linux";

  inputs = {
    flake-commons.url = "github:nxmatic/nix-flake-commons/develop";
    nixpkgs.follows = "flake-commons/nixpkgs";
    headplane.url = "github:tale/headplane?ref=v0.6.2";
    headplane.inputs.flake-utils.follows = "flake-utils";
    headplane.inputs.nixpkgs.follows = "nixpkgs";
    flake-utils.follows = "flake-commons/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    headplane,
    flake-utils,
    ...
  }:
    flake-utils.lib.eachSystem [
      "aarch64-darwin"
      "aarch64-linux"
    ] (system: let
      pkgs = import nixpkgs {
        inherit system;
        overlays = [ self.overlays.default ];
      };
    in {
      packages = {
        headplane = pkgs.headplane;
        headplane-agent = pkgs.headplane-agent;
        headplane-nixos-docs = pkgs.headplane-nixos-docs;
        headplane-ssh-wasm = pkgs.headplane-ssh-wasm;
        headscale = pkgs.headscale;
      };
      defaultPackage = pkgs.headplane;
    })
    // {
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