package io.nxmatic.rke2lab.incus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.incus.ingress.BootstrapPaths;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * The CROSS-DOMAIN proof of the host-asset seam, run IN-CONTAINER where BOTH worlds live in one
 * Felix: incus-core's {@code BootstrapHostAssetMaterializer} @Component (driver) and
 * manifests-core's three {@code HostAssetProvider} @Components (contributors). It proves the wiring
 * neither domain's own tests can — that SCR binds the manifests providers into the incus
 * materializer's {@code @Reference(MULTIPLE)} — and that a real contribute→materialize round-trip
 * places every provider's slice into the instance's staging tree.
 *
 * <p>The binding is asserted by its EFFECT, not by reflecting the private field: a round-trip that
 * materialises all three slots (NoCloud seed, env shell file, systemd bundle) can only succeed if
 * all three providers were bound and invoked — a missing binding drops that slot silently, which is
 * exactly the regression the typed seam exists to abolish (the "no materializer in the registry"
 * failure that broke before).
 */
class HostAssetMaterializerWiringTest {

  private static BundleContext context() {
    return FrameworkUtil.getBundle(HostAssetMaterializerWiringTest.class).getBundleContext();
  }

  @Test
  void theMaterializerActivatesAndManifestsPublishesThreeProviders() throws Exception {
    final BundleContext context = context();

    final ServiceReference<BootstrapHostAssetMaterializer> materializer =
        context.getServiceReference(BootstrapHostAssetMaterializer.class);
    assertNotNull(
        materializer,
        "SCR must publish incus-core's BootstrapHostAssetMaterializer — its @Component activated"
            + " with the manifests HostAssetProviders bound.");

    final Collection<ServiceReference<HostAssetProvider>> providers =
        context.getServiceReferences(HostAssetProvider.class, null);
    assertNotNull(providers, "manifests-core must publish its HostAssetProviders in this Felix");
    assertEquals(
        3,
        providers.size(),
        "the cloud-config, env-config and systemd-bundle providers all register in the SAME registry"
            + " the materializer collects from");
  }

  @Test
  void aRoundTripMaterializesEveryProvidersSlot(@TempDir Path tmp) throws IOException {
    final BootstrapPaths paths =
        BootstrapPaths.fromLocalWorktree(
            tmp.resolve("repo").resolve("worktree"), "bioskop", "master");
    final Path synth = paths.manifestsRoot();

    // Lay the three slices exactly as the manifests synthesis writes them under the manifests root.
    write(
        synth.resolve("runtime/cloud-config/.configmap-cloud-config.yml"),
        configMap(
            "rke2lab-cloud-config",
            """
              userData: |
                #cloud-config
              metaData: |
                local-hostname: master
              networkData: |
                version: 2
            """));
    // A hidden env-section dotfile named exactly as the provider discovers them
    // (.configmap-env-section-<name>.yml — the trailing hyphen distinguishes a section from the
    // sibling .configmap-*.group.yml inventory marker), mirroring the synthesis and
    // manifests-core's own HostAssetProviderContributionTest. A name without the hyphen is NOT an
    // env section, so the provider would contribute nothing and no shell file would land.
    write(
        synth.resolve("runtime/env-config/.configmap-env-section-core.yml"),
        configMap(
            "rke2lab-env",
            """
              RKE2LAB_ROLE: server
            """));
    // The two systemd ConfigMap dotfile names the synthesis writes (mirrors
    // SystemdBundleConfigMaps.{UNITS,SCRIPTS}_DOTFILE — a literal here keeps this cross-domain test
    // off manifests-core's INTERNAL package, which the fragment must not import).
    write(
        synth.resolve("systemd/.configmap-systemd-units.yml"),
        configMap(
            "rke2lab-systemd-units",
            """
              rke2lab.service: |
                [Unit]
                Description=rke2lab
            """));
    write(
        synth.resolve("systemd/.configmap-systemd-scripts.yml"),
        configMap(
            "rke2lab-systemd-scripts",
            """
              rke2lab-activate.sh: |
                #!/bin/sh
                echo hi
            """));

    // Drive the SCR-published materializer (its @Reference-bound providers do the reading).
    final BundleContext context = context();
    final BootstrapHostAssetMaterializer materializer =
        context.getService(context.getServiceReference(BootstrapHostAssetMaterializer.class));
    assertNotNull(materializer, "the materializer service resolves to an instance");
    materializer.materialize(paths);

    // CLOUD_SEED (CloudConfigHostAssetProvider → SEED_DIR): the NoCloud triplet lands.
    assertEquals("#cloud-config\n", Files.readString(paths.cloudSeedRoot().resolve("user-data")));
    assertTrue(
        Files.isRegularFile(paths.cloudSeedRoot().resolve("meta-data")), "meta-data written");
    assertTrue(
        Files.isRegularFile(paths.cloudSeedRoot().resolve("network-config")),
        "network-config written");

    // ENV_CONFIG (EnvConfigHostAssetProvider → SHELL_ENV_FILE): the one sourced env file lands.
    final Path envFile = paths.scriptsRoot().resolve("rke2lab-environment.sh");
    assertTrue(Files.isRegularFile(envFile), "the env shell file was materialised");
    final String env = Files.readString(envFile);
    assertTrue(
        env.contains("set -a") && env.contains("RKE2LAB_ROLE='server'") && env.contains("set +a"),
        "the env vars are wrapped set -a … set +a, single-quoted:\n" + env);

    // SYSTEMD_UNITS + SYSTEMD_SCRIPTS (SystemdBundleHostAssetProvider → CONFIGMAP_FILES): the unit
    // lands in the units root (not executable); the script in the scripts root, executable.
    final Path unit = paths.systemdRoot().resolve("rke2lab.service");
    assertTrue(Files.isRegularFile(unit), "the systemd unit was extracted from the ConfigMap");
    final Path script = paths.scriptsRoot().resolve("rke2lab-activate.sh");
    assertTrue(Files.isRegularFile(script), "the systemd script was extracted from the ConfigMap");
    assertTrue(
        Files.getPosixFilePermissions(script).contains(PosixFilePermission.OWNER_EXECUTE),
        "a scripts-slot file lands executable");
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
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
}
