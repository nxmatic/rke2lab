package io.nxmatic.rke2lab.manifests.hostasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetContribution;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetDeliveryKind;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetSlot;
import io.nxmatic.rke2lab.manifests.systemd.SystemdBundleConfigMaps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The contribution each {@link io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetProvider}
 * derives from its own slice of the synthesised tree — the manifests half of the host-asset seam,
 * white-box in the provider package. A provider reads only its slice and yields raw entries + a
 * delivery kind; incus transforms. A missing slice yields NO contribution (the driver simply places
 * nothing), which is how an absent asset surfaces as an empty provider rather than a silent gap.
 *
 * <p>Pure filesystem — no OSGi. The registration of these @Components and the
 * materializer's @Reference binding are proven separately, in-container.
 */
class HostAssetProviderContributionTest {

  private static Path sliceDir(Path synthRoot, String slice) throws IOException {
    return Files.createDirectories(synthRoot.resolve(slice));
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
  void cloudConfigYieldsOneSeedDirContribution(@TempDir Path synthRoot) throws IOException {
    Files.writeString(
        sliceDir(synthRoot, "runtime/cloud-config").resolve(".configmap-cloud-config.yml"),
        configMap(
            "rke2lab-cloud-config",
            """
              userData: |
                #cloud-config
            """));

    final List<HostAssetContribution> contributions =
        new CloudConfigHostAssetProvider().contribute(synthRoot);

    assertEquals(1, contributions.size(), "one cloud-config contribution");
    final HostAssetContribution seed = contributions.get(0);
    assertEquals(HostAssetSlot.CLOUD_SEED, seed.slot());
    assertEquals(HostAssetDeliveryKind.SEED_DIR, seed.deliveryKind());
    assertEquals(1, seed.entries().size(), "the slice's one yaml file rode across as one entry");
    assertEquals(".configmap-cloud-config.yml", seed.entries().get(0).relativePath());
    assertTrue(seed.entries().get(0).content().contains("#cloud-config"));
  }

  @Test
  void cloudConfigWithoutSliceContributesNothing(@TempDir Path synthRoot) throws IOException {
    assertTrue(
        new CloudConfigHostAssetProvider().contribute(synthRoot).isEmpty(),
        "no slice directory → no contribution (missing asset = missing provider, not a gap)");
  }

  @Test
  void envConfigYieldsOneShellEnvFileContribution(@TempDir Path synthRoot) throws IOException {
    final Path slice = sliceDir(synthRoot, "runtime/env-config");
    Files.writeString(
        slice.resolve(".configmap-env-section-core.yml"),
        configMap(
            "rke2lab-env-core",
            """
              FOO: bar
            """));
    Files.writeString(
        slice.resolve(".configmap-env-section-mesh.yml"),
        configMap(
            "rke2lab-env-mesh",
            """
              BAZ: qux
            """));

    final List<HostAssetContribution> contributions =
        new EnvConfigHostAssetProvider().contribute(synthRoot);

    assertEquals(1, contributions.size(), "the sections fan into ONE env contribution");
    final HostAssetContribution env = contributions.get(0);
    assertEquals(HostAssetSlot.ENV_CONFIG, env.slot());
    assertEquals(HostAssetDeliveryKind.SHELL_ENV_FILE, env.deliveryKind());
    assertEquals(EnvConfigHostAssetProvider.ENV_FILE, env.targetFile());
    assertEquals(2, env.entries().size(), "both hidden section dotfiles rode across");
  }

  @Test
  void envConfigWithoutSliceContributesNothing(@TempDir Path synthRoot) throws IOException {
    assertTrue(
        new EnvConfigHostAssetProvider().contribute(synthRoot).isEmpty(),
        "no env-config slice → no contribution");
  }

  @Test
  void systemdBundleYieldsUnitsAndScriptsContributions(@TempDir Path synthRoot) throws IOException {
    final Path slice = sliceDir(synthRoot, "systemd");
    Files.writeString(
        slice.resolve(SystemdBundleConfigMaps.UNITS_DOTFILE),
        configMap(
            "rke2lab-systemd-units",
            """
              bar.service: |
                [Unit]
            """));
    Files.writeString(
        slice.resolve(SystemdBundleConfigMaps.SCRIPTS_DOTFILE),
        configMap(
            "rke2lab-systemd-scripts",
            """
              foo.sh: |
                #!/bin/sh
            """));

    final List<HostAssetContribution> contributions =
        new SystemdBundleHostAssetProvider().contribute(synthRoot);

    assertEquals(2, contributions.size(), "units and scripts land in different slot roots");
    contributions.forEach(
        c ->
            assertEquals(
                HostAssetDeliveryKind.CONFIGMAP_FILES,
                c.deliveryKind(),
                "the systemd bundle is delivered as ConfigMap-files"));
    assertEquals(
        List.of(HostAssetSlot.SYSTEMD_UNITS, HostAssetSlot.SYSTEMD_SCRIPTS),
        contributions.stream().map(HostAssetContribution::slot).toList(),
        "units first, then scripts (the provider's add order)");
    assertFalse(contributions.get(0).executable(), "unit files are not executable");
    assertTrue(
        contributions.get(1).executable(),
        "the provider marks its scripts contribution executable — incus does not infer it");
  }

  @Test
  void systemdBundleContributesOnlyThePresentDotfiles(@TempDir Path synthRoot) throws IOException {
    // Only the units dotfile exists — the scripts contribution is simply absent, not an error.
    Files.writeString(
        sliceDir(synthRoot, "systemd").resolve(SystemdBundleConfigMaps.UNITS_DOTFILE),
        configMap(
            "rke2lab-systemd-units",
            """
              bar.service: |
                [Unit]
            """));

    final List<HostAssetContribution> contributions =
        new SystemdBundleHostAssetProvider().contribute(synthRoot);

    assertEquals(1, contributions.size(), "only the present dotfile contributes");
    assertEquals(HostAssetSlot.SYSTEMD_UNITS, contributions.get(0).slot());
  }

  @Test
  void systemdBundleWithoutSliceContributesNothing(@TempDir Path synthRoot) throws IOException {
    assertTrue(
        new SystemdBundleHostAssetProvider().contribute(synthRoot).isEmpty(),
        "no systemd slice → no contribution");
  }
}
