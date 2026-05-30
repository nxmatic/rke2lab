{
  description = "RKE2 lab infrastructure and network blueprints";

  inputs = {
    # INVARIANT: nix-darwin-home must NEVER be an input of this flake.
    # The two repos relate in opposite scopes: nix-darwin-home depends on rke2lab
    # at BUILD/eval time (it imports this flake's networkBlueprint as the netplan
    # source of truth), while rke2lab depends on nix-darwin-home only at RUNTIME
    # (its incus instances run on the NixOS host nix-darwin-home provisions). That
    # runtime edge is invisible to nix eval, so there is no cycle. Adding
    # nix-darwin-home here would close the loop into a real flake-eval cycle.
    # Keep the dependency one-directional at the flake level: nix-darwin-home -> rke2lab.

    # Use flake-commons as aggregator to stay synchronized with nix-darwin-home
    flake-commons.url = "github:nxmatic/nix-flake-commons/develop";

    # Follow flake-commons versions
    nixpkgs.follows = "flake-commons/nixpkgs";
    flake-utils.follows = "flake-commons/flake-utils";

    # The flox runtime flake owns the NRI plugin + per-workload package
    # definitions. We re-export its outputs here so the deployable artifacts
    # build through this top-level entry point (and the aarch64-linux NRI plugin
    # cross-builds via the configured linux-builder). It shares nixpkgs/flake-utils
    # so there is a single resolved version set across the two flakes.
    flox-runtime.url = "path:./manifests/src/main/resources/runtime/flox";
    flox-runtime.inputs.nixpkgs.follows = "nixpkgs";
    flox-runtime.inputs.flake-utils.follows = "flake-utils";
    flox-runtime.inputs.flake-commons.follows = "flake-commons";
  };

  outputs = { self, nixpkgs, flake-utils, flox-runtime, ... }:
    let
      # The network blueprint is OS-independent data (cluster/node IDs, MAC
      # patterns, addressing): the netplan jar is a portable JDK/Maven build, so
      # the YAML — and the data parsed from it — is identical regardless of which
      # platform builds it. But IFD must still *realize* the generating
      # derivation on some concrete system, and a flat `lib` (no `lib.${system}`)
      # has no system in scope to pick. Pin generation to the single build host
      # (bioskop = aarch64-darwin): darwin eval builds it natively, and a NixOS
      # eval — which also runs on bioskop — builds the same darwin derivation
      # locally, baking in only the parsed data (pure MAC strings). No
      # linux-builder is involved on this path.
      blueprintSystem = "aarch64-darwin";

      # Single source of truth for the Maven-build toolchain. This one attrset
      # feeds three consumers: the build derivations below, `devShells.default`,
      # and the re-exported `packages` that the flox env pins against — so dev
      # loop, devShell, and store build all resolve the same versions from this
      # flake's nixpkgs. Spotless version-checks the shfmt binary it finds on
      # PATH against its configured `${shfmt.version}`, so the build passes
      # `-Dshfmt.version=${shfmt.version}` to keep binary and config identical,
      # both sourced from this `pkgs`.
      mavenToolchain = pkgs: { inherit (pkgs) jdk25 maven shfmt shellcheck; };
      mavenBuildInputs = pkgs: builtins.attrValues (mavenToolchain pkgs);

      # netplan JAR build, parameterized by pkgs so the per-system `packages`
      # output can build it locally while `lib` uses the pinned set.
      netplanJarFor = pkgs: pkgs.stdenv.mkDerivation {
        name = "rke2lab-netplan";
        src = ./.;  # Need full repo for parent POM + BOM resolution

        nativeBuildInputs = mavenBuildInputs pkgs;

        buildPhase = ''
          # Maven needs a writable HOME for .m2/repository
          mkdir -p $TMPDIR/.m2
          # Install parent POM and BOM first, then build netplan module
          mvn -Dmaven.repo.local=$TMPDIR/.m2/repository \
            install:install-file -Dfile=pom.xml -DpomFile=pom.xml
          mvn -Dmaven.repo.local=$TMPDIR/.m2/repository \
            -f bom/pom.xml install
          mvn -Dmaven.repo.local=$TMPDIR/.m2/repository \
            -f netplan/pom.xml \
            -Dshfmt.version=${pkgs.shfmt.version} clean package -DskipTests
        '';

        installPhase = ''
          mkdir -p $out/share/java
          cp netplan/target/netplan-*-exec.jar $out/share/java/rke2lab-netplan.jar
        '';
      };

      # Generate the network blueprint YAML from the Java source of truth.
      networkBlueprintYamlFor = pkgs:
        let netplanJar = netplanJarFor pkgs;
        in pkgs.stdenv.mkDerivation {
          name = "rke2lab-network-blueprint";

          buildInputs = [ pkgs.jdk25 ];

          dontUnpack = true;

          buildPhase = ''
            # NetplanCli dispatcher routes to yamlExport command
            java -jar ${netplanJar}/share/java/rke2lab-netplan.jar yamlExport > blueprint.yaml
          '';

          installPhase = ''
            mkdir -p $out
            cp blueprint.yaml $out/network-blueprint.yaml
          '';
        };

      # Pinned-system generation + IFD parse → the canonical, flat blueprint data.
      blueprintPkgs = nixpkgs.legacyPackages.${blueprintSystem};
      networkBlueprintYaml = networkBlueprintYamlFor blueprintPkgs;
      networkBlueprintData = builtins.fromJSON (
        builtins.readFile (
          blueprintPkgs.runCommand "blueprint.json" {
            buildInputs = [ blueprintPkgs.yq-go ];
          } ''
            ${blueprintPkgs.yq-go}/bin/yq -o=json '.' ${networkBlueprintYaml}/network-blueprint.yaml > $out
          ''
        )
      );

      # Export network blueprint with Nix helpers. `deriveMacs` uses the
      # system-independent `nixpkgs.lib` (pure int/string functions) so the whole
      # export is platform-agnostic.
      networkBlueprint = networkBlueprintData // {
        # Helper to derive MACs for a cluster/node pair, using the cluster/node
        # ID mappings from the Java-generated YAML.
        deriveMacs = cluster: node:
          let
            clusterId = networkBlueprintData.clusters.${cluster};
            nodeId = networkBlueprintData.nodes.${node};
            toHex = n: if n < 16 then "0${nixpkgs.lib.toLower (nixpkgs.lib.toHexString n)}" else nixpkgs.lib.toLower (nixpkgs.lib.toHexString n);
          in {
            lan = "10:66:6a:4c:${toHex clusterId}:${toHex nodeId}";
            wan = "52:54:00:${toHex clusterId}:${toHex nodeId}:00";
          };
      };
    in
    (flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        netplanJar = netplanJarFor pkgs;

        # Build the seed-master bootstrap app (and the manifests jar it embeds)
        # as a single reactor build, so the deployable artifact Pulumi runs comes
        # from the immutable store rather than a mutable target/. seed-master
        # depends on manifests, netplan, systemd-contract and sdks/incus, so the
        # whole reactor is built once from the parent pom. Mirrors netplanJar's
        # Maven-in-nix pattern; the build runs with sandbox=false (per the host
        # nix.conf) so Maven can resolve its dependency tree.
        seedMasterJar = pkgs.stdenv.mkDerivation {
          name = "rke2lab-seed-master";
          src = ./.;

          nativeBuildInputs = mavenBuildInputs pkgs;

          buildPhase = ''
            mkdir -p $TMPDIR/.m2
            # Pin spotless's shfmt to the flake's binary (see mavenBuildInputs):
            # binary and configured version are both this `pkgs`, so the format
            # gate runs and passes instead of failing on a version mismatch.
            mvn -Dmaven.repo.local=$TMPDIR/.m2/repository \
              -Dshfmt.version=${pkgs.shfmt.version} -DskipTests clean package
          '';

          installPhase = ''
            mkdir -p $out/share/java
            cp seed-master/target/seed-master-*-exec.jar $out/share/java/seed-master.jar
            cp manifests/target/manifests-*-exec.jar $out/share/java/manifests.jar
          '';
        };

        # Per-system inspectable build of the blueprint YAML (the canonical,
        # consumed copy is the pinned one surfaced under the flat `lib`).
        networkBlueprintYaml = networkBlueprintYamlFor pkgs;

        # Darwin-buildable incus client. The full `incus` daemon is Linux-only
        # (requires lxc, libcap, cowsql, etc.), but nixpkgs ships a `client.nix`
        # variant exposed as `pkgs.incus.passthru.client` that builds on both
        # platforms because it only needs Go + the `cmd/incus` subpackage.
        # Surfacing it here gives us a stable `flake = ".#incus-client"`
        # reference for the rke2lab flox env to install on Darwin (the catalog
        # entry only ships Linux builds).
        incusClient = pkgs.incus.passthru.client;

        # NRI plugin (+ debug) re-exported from the flox runtime flake, so the
        # aarch64-linux Go binary builds through this entry point at build time
        # (cross-built via the configured linux-builder) rather than on the node
        # at runtime. The node consumes the resulting store path via its gcroot.
        # Guarded so darwin-only eval of `packages` does not fail when the
        # runtime flake lacks an output for a given system.
        floxRuntimePackages = flox-runtime.packages.${system} or { };
        floxNriPluginPackages =
          (if floxRuntimePackages ? flox-nri-plugin
           then { inherit (floxRuntimePackages) flox-nri-plugin; }
           else { })
          // (if floxRuntimePackages ? flox-nri-plugin-debug
                then { inherit (floxRuntimePackages) flox-nri-plugin-debug; }
                else { });

        # Maven-build toolchain re-exported as individual packages, so the flox
        # env pins each tool to this flake's version
        # (e.g. `shfmt.flake = "github:nxmatic/rke2lab#shfmt"`) instead of the
        # overlapping fleet includes. This flake is the source of truth; flox
        # follows it, keeping the dev loop aligned with the Maven build's
        # spotless gate.
        toolchainPackages = mavenToolchain pkgs;

      in {
        packages = {
          inherit netplanJar networkBlueprintYaml seedMasterJar;
          seed-master = seedMasterJar;
          incus-client = incusClient;
        } // floxNriPluginPackages // toolchainPackages;

        # The declared source of truth for the Maven-build toolchain. `mvn` from
        # here (or via the flox env that consumes these versions) sees the same
        # shfmt/shellcheck the store build does.
        devShells.default = pkgs.mkShell {
          packages = mavenBuildInputs pkgs;
        };
      }
    )) // {
      # Flat, system-independent export consumed by nix-darwin-home as
      # `rke2lab.lib.networkBlueprint.deriveMacs` (no `lib.${system}` selector).
      # Built once on `blueprintSystem`; the data is the same everywhere.
      lib = {
        inherit networkBlueprint;
        # Raw YAML store path for inspection (the pinned, canonical build).
        networkBlueprintYamlPath = "${networkBlueprintYaml}/network-blueprint.yaml";
      };
    };
}
