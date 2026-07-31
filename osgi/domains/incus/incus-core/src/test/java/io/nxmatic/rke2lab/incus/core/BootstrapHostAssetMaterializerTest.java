package io.nxmatic.rke2lab.incus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.incus.ingress.BootstrapPaths;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetContribution;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetDeliveryKind;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetEntry;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetProvider;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetSlot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The strategies the materializer applies to each contribution — SEED_DIR (NoCloud unwrap),
 * CONFIGMAP_FILES (systemd bundle: each data key to a file, executable when the contribution
 * declares it), SHELL_ENV_FILE (env concat). Driven through the package-private {@code
 * materialize(paths, providers)} seam with stub providers, since the in-container scion runs
 * unamended (materialiser is a no-op there).
 */
class BootstrapHostAssetMaterializerTest {

  private static BootstrapPaths pathsUnder(Path tempDir) {
    return BootstrapPaths.fromLocalWorktree(
        tempDir.resolve("repo").resolve("worktree"), "cluster", "node");
  }

  private static HostAssetProvider yielding(HostAssetContribution... contributions) {
    return synthesizedRoot -> List.of(contributions);
  }

  private static String configMap(String name, String dataYaml) {
    return """
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: %s
        data:
        """
            .formatted(name)
        + dataYaml;
  }

  @Test
  void configmap_files_extracts_scripts_executable(@TempDir Path tempDir) throws IOException {
    final BootstrapPaths paths = pathsUnder(tempDir);
    final HostAssetEntry entry =
        HostAssetEntry.file(
            ".configmap-systemd-scripts.yml",
            configMap(
                "rke2lab-systemd-scripts",
                """
                  foo.sh: |
                    #!/bin/sh
                    echo hi
                """));
    new BootstrapHostAssetMaterializer()
        .materialize(
            paths,
            List.of(
                yielding(
                    HostAssetContribution.executableFiles(
                        HostAssetSlot.SYSTEMD_SCRIPTS, List.of(entry)))));

    final Path script = paths.scriptsRoot().resolve("foo.sh");
    assertTrue(Files.isRegularFile(script), "the script was extracted from the ConfigMap");
    assertEquals("#!/bin/sh\necho hi\n", Files.readString(script));
    assertTrue(
        Files.getPosixFilePermissions(script).contains(PosixFilePermission.OWNER_EXECUTE),
        "a scripts-slot file lands executable");
  }

  @Test
  void configmap_files_extracts_units_not_executable(@TempDir Path tempDir) throws IOException {
    final BootstrapPaths paths = pathsUnder(tempDir);
    final HostAssetEntry entry =
        HostAssetEntry.file(
            ".configmap-systemd-units.yml",
            configMap(
                "rke2lab-systemd-units",
                """
                  bar.service: |
                    [Unit]
                    Description=bar
                """));
    new BootstrapHostAssetMaterializer()
        .materialize(
            paths,
            List.of(
                yielding(
                    HostAssetContribution.fanOut(
                        HostAssetSlot.SYSTEMD_UNITS,
                        HostAssetDeliveryKind.CONFIGMAP_FILES,
                        List.of(entry)))));

    final Path unit = paths.systemdRoot().resolve("bar.service");
    assertTrue(Files.isRegularFile(unit), "the unit was extracted from the ConfigMap");
    assertEquals("[Unit]\nDescription=bar\n", Files.readString(unit));
    assertFalse(
        Files.getPosixFilePermissions(unit).contains(PosixFilePermission.OWNER_EXECUTE),
        "a units-slot file is not executable");
  }

  @Test
  void seed_dir_unwraps_the_nocloud_seed(@TempDir Path tempDir) throws IOException {
    final BootstrapPaths paths = pathsUnder(tempDir);
    final HostAssetEntry entry =
        HostAssetEntry.file(
            ".configmap-cloud-config.yml",
            configMap(
                "rke2lab-cloud-config",
                """
                  userData: |
                    #cloud-config
                  metaData: hi
                  networkData: net
                """));
    new BootstrapHostAssetMaterializer()
        .materialize(
            paths,
            List.of(
                yielding(
                    HostAssetContribution.fanOut(
                        HostAssetSlot.CLOUD_SEED,
                        HostAssetDeliveryKind.SEED_DIR,
                        List.of(entry)))));

    assertEquals("#cloud-config\n", Files.readString(paths.cloudSeedRoot().resolve("user-data")));
    assertEquals("hi", Files.readString(paths.cloudSeedRoot().resolve("meta-data")));
    assertEquals("net", Files.readString(paths.cloudSeedRoot().resolve("network-config")));
  }

  @Test
  void shell_env_file_wraps_the_env_vars(@TempDir Path tempDir) throws IOException {
    final BootstrapPaths paths = pathsUnder(tempDir);
    final HostAssetEntry entry =
        HostAssetEntry.file(
            ".configmap-env-section-core.yml",
            configMap(
                "rke2lab-env",
                """
                  FOO: bar
                  BAZ: qux
                """));
    new BootstrapHostAssetMaterializer()
        .materialize(
            paths,
            List.of(
                yielding(
                    HostAssetContribution.shellEnvFile(
                        HostAssetSlot.ENV_CONFIG, List.of(entry), "rke2lab-environment.sh"))));

    final String env = Files.readString(paths.scriptsRoot().resolve("rke2lab-environment.sh"));
    assertEquals("set -a\nFOO='bar'\nBAZ='qux'\nset +a\n", env);
  }
}
