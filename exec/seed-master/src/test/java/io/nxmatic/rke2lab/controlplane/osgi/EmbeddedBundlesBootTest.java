package io.nxmatic.rke2lab.controlplane.osgi;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.junit.testkit.Osgi;
import io.nxmatic.rke2lab.manifests.port.FloxRuntimeAssetService;
import io.nxmatic.rke2lab.manifests.port.ManifestDocumentService;
import io.nxmatic.rke2lab.manifests.port.ManifestExplodeService;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisService;
import io.nxmatic.rke2lab.manifests.port.ManifestUpdateGate;
import io.nxmatic.rke2lab.manifests.port.node.NodeEnvOverlayService;
import io.nxmatic.rke2lab.osgi.runtime.BootPipeline;
import io.nxmatic.rke2lab.osgi.runtime.BootedFramework;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * WI-C packaging proof: boot the framework from the bundles EMBEDDED in this artifact under {@code
 * META-INF/bundles/} — the exact deployed topology — via {@code BootPipeline.embedded()}, the same
 * staged source a deployed exec-jar boots from. The {@code stage-embedded-bundles}
 * dependency-plugin execution copies the jars into {@code generated-resources}, a resource root, so
 * they are on the test classpath here just as they are inside the deployed exec-jar; the pipeline
 * streams each staged jar straight into Felix.
 *
 * <p>This closes the gap a {@code jar tf} check leaves open: it proves the embedded jars actually
 * boot, resolve, and publish their services — not merely that they are present in the archive.
 *
 * <p>The proof is TYPED: every seam service is resolved as its {@code -port} interface and cast.
 * That only works if the host and the installed manifests-core bundle share ONE copy of each port
 * class — the single-exporter trick {@code BootPlanner} applies by mirroring manifests-core's bnd
 * Import-Package onto the system bundle. A split (two class copies) would surface as {@code
 * awaitService} returning null or a {@code ClassCastException}.
 */
@Osgi
class EmbeddedBundlesBootTest {

  private static BootedFramework framework;

  @BeforeAll
  static void bootFromEmbeddedBundles() {
    assertTrue(
        BootPipeline.hasEmbeddedBundles(),
        "the stage-embedded-bundles execution must have placed the jars under META-INF/bundles");
    framework = BootPipeline.embedded().launch();
  }

  @AfterAll
  static void stopFelix() {
    if (framework != null) {
      framework.close();
    }
  }

  @Test
  void embeddedManifestsCorePublishesItsServicesTyped() {
    assertNotNull(
        framework.awaitService(ManifestSynthesisService.class, 5000),
        "the embedded manifests-core bundle booted and SCR published ManifestSynthesisService"
            + " (its @Reference Resolver bound from the embedded felix.resolver)");
    assertNotNull(
        framework.awaitService(ManifestExplodeService.class, 5000),
        "SCR published ManifestExplodeService");
    assertNotNull(
        framework.awaitService(ManifestUpdateGate.class, 5000),
        "the embedded manifests-core bundle published ManifestUpdateGate (the R3-deferred gate)");
  }

  @Test
  void embeddedManifestsCorePublishesTheMilestoneBServicesTyped() {
    assertNotNull(
        framework.awaitService(NodeEnvOverlayService.class, 5000),
        "SCR published NodeEnvOverlayService (couture 1)");
    assertNotNull(
        framework.awaitService(ManifestDocumentService.class, 5000),
        "SCR published ManifestDocumentService (couture 2)");
    assertNotNull(
        framework.awaitService(FloxRuntimeAssetService.class, 5000),
        "SCR published FloxRuntimeAssetService (couture 3)");
  }
}
