{
  description = "RKE2 lab infrastructure and network blueprints";

  # The seed-master build reuses the host `~/.m2` (resolved via getEnv "HOME")
  # to reach private GitHub Packages deps without plumbing a token into the
  # sandbox, so its eval is necessarily impure. Declare that here — like
  # extra-substituters, nixConfig is applied before outputs are evaluated, so
  # `nix build .#seed-master` works without a manual `--impure`. This only takes
  # effect when rke2lab is the TOP-LEVEL flake; when consumed as an input (e.g.
  # nix-darwin-home reading `lib.networkBlueprint`), it is ignored and that pure
  # path never forces the impure getEnv. First use prompts to trust the config
  # unless `accept-flake-config = true` is set.
  nixConfig.pure-eval = false;

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
    flox-runtime.url = "path:./osgi/manifests/manifests/src/main/resources/runtime/flox";
    flox-runtime.inputs.nixpkgs.follows = "nixpkgs";
    flox-runtime.inputs.flake-utils.follows = "flake-utils";
    flox-runtime.inputs.flake-commons.follows = "flake-commons";
  };

  outputs = inputs@{ self, nixpkgs, flake-utils, flox-runtime, ... }:
    let
      # Enforce the INVARIANT above mechanically, not just by comment: fail eval
      # (any `nix build`/`nix eval` of this flake) with a printed diagnostic if
      # nix-darwin-home is ever wired in as an input. The `...` in the argument
      # set would otherwise swallow it silently. The dependency must stay
      # one-directional — nix-darwin-home -> rke2lab — so this edge never closes
      # into a flake-eval cycle.
      _invariantGuard =
        if inputs ? nix-darwin-home then
          throw ''
            rke2lab flake INVARIANT violated: `nix-darwin-home` is an input.
            The two repos relate in opposite scopes — nix-darwin-home depends on
            rke2lab at build/eval time (it imports lib.networkBlueprint), while
            rke2lab depends on nix-darwin-home only at runtime (incus instances
            run on the host it provisions). Adding nix-darwin-home as an input
            here closes that into a real flake-eval cycle. Remove the input;
            keep the dependency one-directional: nix-darwin-home -> rke2lab.
          ''
        else
          null;

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
      #
      # `which` is required: spotless locates shfmt by shelling out to
      # `which shfmt` (ForeignExe, when no <pathToExe> is set) and reports a
      # non-zero exit as "Unable to find shfmt on path". The nix stdenv has no
      # `which`, so without it the gate fails even though shfmt is on PATH; the
      # dev loop never hit this because flox/the system provides `which`.
      mavenToolchain = pkgs: { inherit (pkgs) jdk25 maven shfmt shellcheck which; };
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
          # Install parent POM and BOM first, then build the netplan CLI module.
          # netplan-cli depends on the pure netplan core (osgi/netplan), so it
          # builds through the reactor (`-pl :netplan-cli -am`) rather than a
          # standalone `-f` — `-am` pulls the core sibling from source.
          mvn -Dmaven.repo.local=$TMPDIR/.m2/repository \
            install:install-file -Dfile=pom.xml -DpomFile=pom.xml
          mvn -Dmaven.repo.local=$TMPDIR/.m2/repository \
            -f bom/pom.xml install
          mvn -Dmaven.repo.local=$TMPDIR/.m2/repository \
            -pl :netplan-cli -am \
            -Dshfmt.version=${pkgs.shfmt.version} clean package -DskipTests
        '';

        installPhase = ''
          mkdir -p $out/share/java
          cp exec/netplan-cli/target/netplan-cli-*-exec.jar $out/share/java/rke2lab-netplan.jar
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
    # Force the invariant guard before returning any output, so a forbidden
    # nix-darwin-home input fails eval with the diagnostic rather than slipping by.
    builtins.seq _invariantGuard
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
        #
        # The reactor depends on private GitHub Packages artifacts
        # (io.nxmatic:java-bbox-api-client, java-systemd) that need auth. Rather
        # than plumb a token into the sandbox, reuse the host `~/.m2/repository`,
        # which the dev loop has already populated with these (release) artifacts.
        # The build user can't WRITE to the host repo, so it can't be the primary
        # local repo (Maven writes tracking files there → AccessDeniedException).
        # Instead use Maven 3.9's chained local repo: a writable $TMPDIR primary
        # plus the host repo as a READ-ONLY tail (`maven.repo.local.tail`).
        # Private deps resolve from the read-only tail (no write attempt, no 401);
        # public deps read through and download into the temp primary. This makes
        # the build impure (reads host state via `getEnv "HOME"`); the top-level
        # `nixConfig.pure-eval = false` lets `nix build .#seed-master` run it
        # without a manual `--impure`.
        hostM2Settings = "${builtins.getEnv "HOME"}/.m2/settings";
        hostM2Repo = "${builtins.getEnv "HOME"}/.m2/repository";
        hostGHToken = "${builtins.getEnv "GH_TOKEN"}";
        seedMasterJar = pkgs.stdenv.mkDerivation {
          name = "rke2lab-seed-master";
          src = ./.;

          nativeBuildInputs = mavenBuildInputs pkgs;

          buildPhase = ''
            set -euo pipefail
            M2_REPO=$TMPDIR/.m2/repository
            mkdir -p $M2_REPO

            # Reuse strategy (see above): writable $TMPDIR primary + host repo as
            # read-only tail (`maven.repo.local.tail`). The tail serves released
            # deps (e.g. io.nxmatic:java-bbox-api-client) and is auth-backed via
            # the host settings + GH_TOKEN for GitHub Packages.
            #
            # EXCEPTION: locally-`mvn install`ed SNAPSHOTs (here
            # com.github.thjomnx:java-systemd:3.0.0-SNAPSHOT — the upstream
            # project, present in no remote repo) cannot be served from the tail:
            # a tail repo's manager won't treat a `maven-metadata-local.xml`
            # artifact as locally installed, so for a SNAPSHOT it demands remote
            # timestamped metadata that doesn't exist → "Could not find"
            # (ignoreAvailability doesn't help — it flips availability, not the
            # local-install determination). Seed such artifacts into the writable
            # primary, copying `_remote.repositories` so Maven still reads them as
            # local installs. Add more lines here if other local-only deps appear.
            #
            # NOTE: the multi-user nix build user must be able to traverse the
            # host home to read the tail/seed source. nix-darwin-home grants this
            # (home.activation.ensureHomeTraversable: `chmod a+x $HOME`); if the
            # build can't reach `~/.m2`, that step hasn't run.
            for a in com/github/thjomnx/java-systemd; do
              src=${hostM2Repo}/$a
              if [ -d "$src" ]; then
                mkdir -p "$M2_REPO/$(dirname "$a")"
                cp -r "$src" "$M2_REPO/$a"
                chmod -R u+w "$M2_REPO/$a"
              fi
            done

            # Pin spotless's shfmt to the flake binary so its version-check
            # matches the binary on PATH.
            env GH_TOKEN="${hostGHToken}" mvn \
              -Dmaven.settings="${hostM2Settings}" \
              -Dmaven.repo.local="$M2_REPO" \
              -Dmaven.repo.local.tail="${hostM2Repo}" \
              -Dmaven.repo.local.tail.ignoreAvailability=true \
              -Dshfmt.version=${pkgs.shfmt.version} -DskipTests clean package
          '';

          installPhase = ''
            mkdir -p $out/share/java
            cp exec/seed-master/target/seed-master-*-exec.jar $out/share/java/seed-master.jar
            cp exec/manifests-cli/target/manifests-cli-*-exec.jar $out/share/java/manifests.jar
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

        # Prebuilt pulumi CLI for the deploy wrapper (matches the flox env's
        # `pulumi.pkg-path = "pulumi-bin"`); the `-bin` variant avoids a Go
        # compile and tracks the same release line the dev loop uses.
        pulumiPkg = pkgs.pulumi-bin;

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

        # Release deploy: `nix run .#deploy -- <stack>`. Runs `pulumi up` against
        # the STORE-built seed-master jar instead of the mutable Maven target the
        # dev loop uses. A Pulumi project is more than Pulumi.yaml — the stack
        # config (Pulumi.<stack>.yaml, committed) and stack state
        # (.pulumi-state/, the local backend) plus the flox PULUMI_* env all live
        # in the repo working dir — so we deploy IN PLACE and only swap the one
        # thing that differs between dev and release: Pulumi.yaml's `binary`,
        # pointed at the store path for the duration (restored on exit, even on
        # failure). Pulumi can't run from /nix/store (needs a writable cwd for
        # state + plugin cache), which is exactly why we don't copy a skeleton.
        #
        # Deterministic, self-contained: every dependency pulumi needs to run is
        # a nix build input (runtimeInputs) — NOT inherited from the flox env
        # (`nix run` doesn't propagate the caller's PATH anyway). pulumi itself,
        # a JRE to run the seed-master jar, and the incus client the provider
        # shells out to. The PULUMI_* vars are set here too, mirroring the flox
        # env's [vars]: local file:// backend under the repo, empty passphrase.
        deployApp = pkgs.writeShellApplication {
          name = "rke2lab-deploy";
          runtimeInputs = [
            pkgs.coreutils
            pkgs.nix
            pkgs.git
            pulumiPkg
            pkgs.jdk25
            incusClient
          ];
          text = ''
            if [ ! -f Pulumi.yaml ]; then
              echo "error: run from the rke2lab repo root (no Pulumi.yaml here)" >&2
              exit 1
            fi

            stack="''${1:-}"
            if [ -z "$stack" ]; then
              echo "usage: nix run .#deploy -- <stack> [preview|up] [pulumi args...]" >&2
              echo "  preview  read-only diff, no apply (default)" >&2
              echo "  up       apply, non-interactive (pulumi up --yes)" >&2
              exit 2
            fi
            shift

            # Action verb (default: preview — safe, read-only). `up` applies
            # non-interactively: preview is the inspection step, so an explicit
            # `up` means apply and the confirm prompt is redundant. Anything
            # after the verb is passed through to pulumi (e.g. --diff, --target).
            action="''${1:-preview}"
            case "$action" in
              preview | up) shift ;;
              -*) action="preview" ;;  # no verb given, first arg is a pulumi flag
              *) echo "error: unknown action '$action' (preview|up)" >&2; exit 2 ;;
            esac

            # Pulumi project env (mirrors the flox env's [vars]); each
            # `''${VAR:-default}` so an active flox env's values win when present.
            # PULUMI_HOME defaults to the XDG location (NOT the repo) so the
            # plugin/cache dir never becomes a repo artifact, matching flox.
            export PULUMI_HOME="''${PULUMI_HOME:-''${XDG_STATE_HOME:-$HOME/.local/state}/pulumi}"
            export PULUMI_BACKEND_URL="''${PULUMI_BACKEND_URL:-file://$PWD/.pulumi-state}"
            export PULUMI_CONFIG_PASSPHRASE="''${PULUMI_CONFIG_PASSPHRASE:-}"
            mkdir -p "$PULUMI_HOME" .pulumi-state

            # Self-heal: a previous run killed before its restore trap fired can
            # leave Pulumi.yaml's binary pointing at a store path. Restore the
            # committed (Maven-target) file from git before swapping again.
            if grep -qE '^ *binary: /nix/store/' Pulumi.yaml; then
              echo "==> Pulumi.yaml left swapped by a prior run; restoring from git" >&2
              git checkout -- Pulumi.yaml
            fi

            # Flags for the inner `nix build .#seed-master` go via
            # DEPLOY_NIX_FLAGS, e.g. `DEPLOY_NIX_FLAGS='-L -v -v' nix run .#deploy -- dev`.
            # Flags given to the OUTER `nix run` (e.g. `nix run .#deploy -L ...`)
            # are consumed by nix to build/run THIS wrapper; the seed-master
            # build is a separate child `nix` process and does not inherit them.
            read -r -a nixFlags <<< "''${DEPLOY_NIX_FLAGS:-}"

            echo "==> building seed-master from the store" >&2
            jar="$(nix build .#seed-master "''${nixFlags[@]}" --no-link --print-out-paths)/share/java/seed-master.jar"
            [ -f "$jar" ] || { echo "error: store jar not found at $jar" >&2; exit 1; }

            # Swap Pulumi.yaml's binary to the store jar for the duration; always
            # restore the committed file on exit so the dev loop is untouched.
            # Trap signals too (not just EXIT) so a Ctrl-C during `pulumi up`
            # still restores; the self-heal above covers a hard kill (-9).
            backup="$(mktemp)"
            cp Pulumi.yaml "$backup"
            trap 'cp "$backup" Pulumi.yaml; rm -f "$backup"' EXIT INT TERM HUP
            sed -E "s#^( *binary: ).*#\1$jar#" "$backup" > Pulumi.yaml

            case "$action" in
              preview)
                echo "==> pulumi preview --stack $stack (binary=$jar)" >&2
                pulumi preview --stack "$stack" "$@"
                ;;
              up)
                echo "==> pulumi up --yes --stack $stack (binary=$jar)" >&2
                pulumi up --yes --stack "$stack" "$@"
                ;;
            esac
          '';
        };

      in {
        packages = {
          inherit netplanJar networkBlueprintYaml seedMasterJar;
          seed-master = seedMasterJar;
          incus-client = incusClient;
          deploy = deployApp;
        } // floxNriPluginPackages // toolchainPackages;

        apps.deploy = {
          type = "app";
          program = "${deployApp}/bin/rke2lab-deploy";
        };

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
