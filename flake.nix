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
    # flox baked into the NixOS node substrate (nixosConfigurations.rke2-node-base) as a nix
    # derivation, replacing the Debian-era runtime installer. Same source the family already
    # locks (github:flox/flox), via the aggregator so it stays in sync with nix-darwin-home.
    flox.follows = "flake-commons/flox";

    # The flox runtime flake owns the NRI plugin + per-workload package
    # definitions. We re-export its outputs here so the deployable artifacts
    # build through this top-level entry point (and the aarch64-linux NRI plugin
    # cross-builds via the configured linux-builder). It shares nixpkgs/flake-utils
    # so there is a single resolved version set across the two flakes.
    flox-runtime.url = "path:./osgi/domains/manifests/manifests-core/src/main/resources/runtime/flox";
    flox-runtime.inputs.nixpkgs.follows = "nixpkgs";
    flox-runtime.inputs.flake-utils.follows = "flake-utils";
    flox-runtime.inputs.flake-commons.follows = "flake-commons";
  };

  outputs = inputs@{ self, nixpkgs, flake-utils, flox-runtime, flox, ... }:
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

      # Host ~/.m2 reuse for the Maven-in-nix builds. These read the host env at eval
      # time — they resolve ONLY when the eval is IMPURE. rke2lab as the top-level flake
      # has `nixConfig.pure-eval = false`, but when nix-darwin-home consumes it as an
      # input (building netplanJar via lib.networkBlueprint) that config is ignored and
      # the eval is PURE → getEnv returns "" → hostM2Repo becomes "/.m2/repository" and
      # hostGHToken "". So that path MUST be evaluated impurely: `darwin-rebuild … --impure`
      # (or the equivalent on the consuming flake). Without it the seed below copies
      # nothing and the build dies resolving staging-extension from central.
      hostM2Repo = "${builtins.getEnv "M2_REPO"}";
      hostGHToken = "${builtins.getEnv "GH_TOKEN"}";

      # The io.nxmatic closure the `.mvn/extensions.xml` core extension needs at
      # bootstrap: staging-extension + its bnd-read dep + the parent-POM chain
      # (aggregator maven-embed-staging-ext → build-parent → root rke2lab). Model
      # building resolves every link BEFORE any POM, by the BootstrapCoreExtensionManager
      # — which does NOT consult maven.repo.local.tail (the tail is wired into the main
      # build's resolver only). So the whole closure must be seeded into the PRIMARY,
      # from the host ~/.m2 where it is `mvn install`ed.
      stagingExtensionClosure = [
        "io/nxmatic/rke2lab/staging-extension"
        "io/nxmatic/rke2lab/bnd-read"
        "io/nxmatic/rke2lab/maven-embed-staging-ext"
        "io/nxmatic/rke2lab/build-parent"
        "io/nxmatic/rke2lab/rke2lab"
      ];

      # Shared Maven-in-nix plumbing for both reactor derivations: a buildPhase prelude
      # that sets up a writable $M2_REPO, SEEDS the staging-extension closure into it
      # (mechanism 1 below), and defines `mvnHost` — an `mvn` wrapper pinning that primary
      # + the host ~/.m2 as a READ-ONLY tail (mechanism 2), with GH_TOKEN. Each derivation
      # opens its buildPhase with `${mavenHostPrelude}`, then calls `mvnHost <goals>`.
      #
      # (1) The CORE extension resolves at bootstrap from the seeded primary (the tail is
      #     invisible to that early resolver — see stagingExtensionClosure).
      # (2) The MAIN build's released private deps (java-bbox-api-client, java-systemd
      #     3.0.0-rc.2) come from the host ~/.m2 as a read-only tail — the build user can't
      #     WRITE the host repo (Maven tracking files → AccessDeniedException), so it's a
      #     tail, not the primary; public deps download into the temp primary.
      #
      # Both mechanisms read host state (hostM2Repo) → the eval must be impure (see above).
      # The seed guard turns an empty getEnv into a clear error instead of a cryptic
      # "staging-extension not found in central" 200 lines later.
      mavenHostPrelude = ''   
        : "Point M2_REPO at the writable build dir so the JVM's user.home-derived default"
        : "local repo ($HOME/.m2/repository\) IS the seeded primary — covers the bootstrap"
        : "resolver whether it honors -Dmaven.repo.local or falls back to user.home. The"
        : "tail path ($hostM2Repo) is baked at eval time, so this override doesn't touch it."
        export HOME="$TMPDIR"
        M2_REPO="$HOME/.m2/repository"
        mkdir -p "$M2_REPO"

        for a in ${builtins.concatStringsSep " " stagingExtensionClosure}; do
          src=${hostM2Repo}/$a
          if [ -d "$src" ]; then
            mkdir -p "$M2_REPO/$(dirname "$a")"
            cp -r "$src" "$M2_REPO/$a"
            chmod -R u+w "$M2_REPO/$a"
          fi
        done

        if [ ! -d "$M2_REPO/io/nxmatic/rke2lab/staging-extension" ]; then
          cat <<EoE
          FATAL: staging-extension not seeded from '${hostM2Repo}'." >&2
          That path has no ~/.m2 artifacts. Either:
            - pass your M2_REPO and GH_TOKEN lathrough, e.g.:
                 sudo env M2_REPO="\$HOME" GH_TOKEN="\$(gh auth token)"
                   darwin-rebuild switch --impure --flake .#nikopol
        EoE
          exit 1
        fi  

        mvnHost() {
          env GH_TOKEN="${hostGHToken}" mvn \
            -Dmaven.repo.local="$M2_REPO" \
            -Dmaven.repo.local.tail="${hostM2Repo}" \
            -Dmaven.repo.local.tail.ignoreAvailability=true \
            "$@"
        }
      '';

      # netplan JAR build, parameterized by pkgs so the per-system `packages`
      # output can build it locally while `lib` uses the pinned set.
      netplanJarFor = pkgs: pkgs.stdenv.mkDerivation {
        name = "rke2lab-netplan";
        src = ./.;  # Need full repo for parent POM + BOM resolution

        nativeBuildInputs = mavenBuildInputs pkgs;

        buildPhase = ''
          ${mavenHostPrelude}

          # Install parent POM and BOM first, then build the netplan CLI module.
          # netplan-cli depends on the pure netplan core (osgi/netplan/netplan-core),
          # so it builds through the reactor (`-pl :netplan-cli -am`) rather than a
          # standalone `-f` — `-am` pulls the core sibling from source.
          mvnHost install:install-file -Dfile=pom.xml -DpomFile=pom.xml
          mvnHost -f build-parent/pom.xml install
          mvnHost -f bom/pom.xml install
          mvnHost -pl :netplan-cli -am \
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

      # The consumed blueprint is PURE committed data — read from the checked-in
      # network-blueprint.json, no IFD, no build. This is what lets any system evaluate
      # `lib.networkBlueprint` without realizing the darwin-pinned netplan jar — notably
      # an aarch64-linux nikopol-nixos with no darwin builder. The Java stays the source
      # of truth, materialized into the JSON at regen time on a jar-capable host:
      #   nix build .#networkBlueprintJson && cp result network-blueprint.json && commit
      networkBlueprintData = builtins.fromJSON (builtins.readFile ./network-blueprint.json);

      # Regeneration only (NOT on the consumed data path): the jar-built YAML on the
      # pinned blueprintSystem, surfaced via networkBlueprintYamlPath for inspection.
      # The blueprintSystem darwin pin now affects ONLY regeneration, never consumption.
      blueprintPkgs = nixpkgs.legacyPackages.${blueprintSystem};
      networkBlueprintYaml = networkBlueprintYamlFor blueprintPkgs;

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
        # Maven-in-nix pattern (shared `mavenHostPrelude` — full rationale there);
        # the build runs with sandbox=false (per the host nix.conf) so Maven can
        # resolve its dependency tree. java-systemd 3.0.0-rc.2 is a release now, so
        # the tail serves it — no seed needed beyond the staging-extension closure.
        seedMasterJar = pkgs.stdenv.mkDerivation {
          name = "rke2lab-seed-master";
          src = ./.;

          nativeBuildInputs = mavenBuildInputs pkgs;

          buildPhase = ''
            ${mavenHostPrelude}

            # Pin spotless's shfmt to the flake binary so its version-check
            # matches the binary on PATH.
            mvnHost -Dshfmt.version=${pkgs.shfmt.version} -DskipTests clean package
          '';

          installPhase = ''
            mkdir -p $out/share/java
            cp exec/seed-master/target/seed-master-*-exec.jar $out/share/java/seed-master.jar
            cp exec/manifests-cli/target/manifests-cli-*-exec.jar $out/share/java/manifests.jar
          '';
        };

        # Per-system inspectable build of the blueprint YAML, plus its JSON projection.
        # networkBlueprintJson is the REGEN artifact for the checked-in data file — run
        # on a jar-capable host (darwin/bioskop): `nix build .#networkBlueprintJson`,
        # then `cp result network-blueprint.json` and commit. The consumed data reads
        # that committed JSON purely (see the flat `lib`), so this is off the eval path.
        networkBlueprintYaml = networkBlueprintYamlFor pkgs;
        networkBlueprintJson = pkgs.runCommand "network-blueprint.json" {
          nativeBuildInputs = [ pkgs.yq-go ];
        } ''
          yq -o=json '.' ${networkBlueprintYamlFor pkgs}/network-blueprint.yaml > $out
        '';

        # Darwin-buildable incus client. The full `incus` daemon is Linux-only
        # (requires lxc, libcap, cowsql, etc.), but nixpkgs ships a `client.nix`
        # variant exposed as `pkgs.incus.passthru.client` that builds on both
        # platforms because it only needs Go + the `cmd/incus` subpackage.
        # Surfacing it here gives us a stable `flake = ".#incus-client"`
        # reference for the rke2lab flox env to install on Darwin (the catalog
        # entry only ships Linux builds).
        incusClient = pkgs.incus.passthru.client;

        # distrobuilder, surfaced as a pinned flake package so the remote image
        # builder resolves it with `nix build .#distrobuilder` instead of a full
        # `flox activate` of the rke2lab dev env — whose k8s include drags a
        # from-source ceph-client build onto the builder just to put ONE binary on
        # PATH. Linux-only in nixpkgs (it builds Linux rootfs), so guarded like the
        # flox-nri plugin above to keep the darwin `packages` eval clean.
        distrobuilderPackages =
          if pkgs.stdenv.isLinux then { distrobuilder = pkgs.distrobuilder; } else { };

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
          inherit netplanJar networkBlueprintYaml networkBlueprintJson seedMasterJar;
          seed-master = seedMasterJar;
          incus-client = incusClient;
          deploy = deployApp;
        } // floxNriPluginPackages // toolchainPackages // distrobuilderPackages;

        apps.deploy = {
          type = "app";
          program = "${deployApp}/bin/rke2lab-deploy";
          meta.description = "Build the seed-master jar and run pulumi preview/up against it";
        };

        # Regenerate the committed network-blueprint.json from the netplan jar. Run on a
        # jar-capable host (bioskop/darwin) from the repo root: `nix run .#regen-blueprint`.
        # The Java stays the source of truth; this materializes it into the checked-in file
        # that every system then reads purely (no darwin builder on the consume path).
        apps.regen-blueprint = {
          type = "app";
          program = toString (pkgs.writeShellScript "regen-blueprint" ''
            set -euo pipefail
            cat ${networkBlueprintJson} > network-blueprint.json
            echo "regenerated network-blueprint.json from ${networkBlueprintJson}"
          '');
          meta.description = "Regenerate the committed network-blueprint.json from the netplan jar";
        };

        # Anti-drift gate: fail if the committed JSON diverges from the jar output
        # (compared canonically via jq -S, so formatting never trips it). Defined ONLY on
        # blueprintSystem — absent elsewhere, so `nix flake check` on an aarch64-linux node
        # never realizes the darwin-pinned jar.
        checks = pkgs.lib.optionalAttrs (system == blueprintSystem) {
          blueprint-fresh =
            pkgs.runCommand "blueprint-fresh" { nativeBuildInputs = [ pkgs.jq ]; } ''
              jq -S . ${./network-blueprint.json} > committed.json
              jq -S . ${networkBlueprintJson} > fresh.json
              if ! diff -u committed.json fresh.json; then
                echo "network-blueprint.json is STALE vs the netplan Java source." >&2
                echo "Regenerate on this jar-capable host: nix run .#regen-blueprint" >&2
                exit 1
              fi
              touch $out
            '';
        };

        # The declared source of truth for the Maven-build toolchain. `mvn` from
        # here (or via the flox env that consumes these versions) sees the same
        # shfmt/shellcheck the store build does.
        devShells.default = pkgs.mkShell {
          packages = mavenBuildInputs pkgs;
        };
      }
    )) // {
      # The NixOS node substrate — the immutable Incus container image every RKE2 node
      # boots from (docs/architecture/nixos-substrate/target-vision.adoc). System-pinned to
      # aarch64-linux (Incus containers on the Apple-Silicon hypervisor); build via bioskop-nixos.
      # Build the artifacts: `.config.system.build.{squashfs,metadata}`, then
      # `incus image import <metadata>/tarball/*.tar.xz <squashfs> --alias rke2lab/node-base`.
      # Per-node identity (node-ip, hostname, token) is injected at instance creation, not baked.
      nixosConfigurations.rke2-node-base = nixpkgs.lib.nixosSystem {
        system = "aarch64-linux";
        specialArgs = { inherit flox; };
        modules = [
          "${nixpkgs}/nixos/modules/virtualisation/lxc-container.nix"
          ./nixos/node-base.nix
        ];
      };

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
