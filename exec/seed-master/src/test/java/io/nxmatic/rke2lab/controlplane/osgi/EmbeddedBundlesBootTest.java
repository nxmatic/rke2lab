package io.nxmatic.rke2lab.controlplane.osgi;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.auth.port.AuthTokenContact;
import io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact;
import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.manifests.port.FloxRuntimeAssetService;
import io.nxmatic.rke2lab.manifests.port.ManifestDocumentService;
import io.nxmatic.rke2lab.manifests.port.ManifestExplodeService;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisRequest;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisResult;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisService;
import io.nxmatic.rke2lab.manifests.port.ManifestUpdateGate;
import io.nxmatic.rke2lab.manifests.port.node.NodeEnvOverlayService;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.nxmatic.rke2lab.osgi.runtime.framework.FrameworkLaunchPipeline;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * WI-C packaging proof: boot the framework from the bundles EMBEDDED in this artifact under {@code
 * META-INF/bundles/} — the exact deployed topology — via {@code
 * FrameworkLaunchPipeline.embedded()}, the same staged source a deployed exec-jar boots from. The
 * {@code stage-embedded-bundles} dependency-plugin execution copies the jars into {@code
 * generated-resources}, a resource root, so they are on the test classpath here just as they are
 * inside the deployed exec-jar; the pipeline streams each staged jar straight into Felix.
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
@OsgiWorld
class EmbeddedBundlesBootTest {

  private static BootedFramework framework;

  @BeforeAll
  static void bootFromEmbeddedBundles() {
    assertTrue(
        FrameworkLaunchPipeline.hasEmbeddedBundles(),
        "the stage-embedded-bundles execution must have placed the jars under META-INF/bundles");
    framework = FrameworkLaunchPipeline.embedded().launch();
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

  @Test
  void embeddedClusterEdgePublishesTheReadinessContactTyped() {
    assertNotNull(
        framework.awaitService(ClusterReadinessContact.class, 5000),
        "the embedded cluster-edge bundle booted and SCR published ClusterReadinessContact — the"
            + " host resolves the kubectl contact from the registry (cluster-port seam"
            + " single-exporter, typed, no ClassCastException)");
  }

  @Test
  void embeddedAuthEdgePublishesTheTokenContactTyped() {
    assertNotNull(
        framework.awaitService(AuthTokenContact.class, 5000),
        "the embedded auth-edge bundle booted and SCR published AuthTokenContact — the host"
            + " launch-secrets updater resolves the gh/flox token contact from the registry"
            + " (auth-port seam single-exporter, typed, no ClassCastException)");
  }

  /**
   * The coverage the {@code awaitService} proofs above do NOT give: they assert SCR PUBLISHES the
   * synthesis service, never that it RUNS. {@code pulumi preview} boots this exact staged topology
   * and then synthesizes — failing in the "cdk8s setup" topic with {@code
   * ServiceConfigurationError: JavaTimeModule not a subtype}, because jsii-runtime's {@code
   * ObjectMapper} drives a {@code ServiceLoader<com.fasterxml.jackson.databind.Module>} that
   * resolves jackson-databind on one classloader and the runtime-discovered jsr310 {@code
   * JavaTimeModule} on another. Calling {@code synthesize} here, on the staged bundles, exercises
   * that path as a build failure — closing the gap between "boots + publishes" and "actually
   * synthesizes".
   */
  @Test
  void embeddedSynthesisRunsTheCdk8sSetupPathOnTheStagedJackson() throws Exception {
    final ManifestSynthesisService synthesis =
        framework.awaitService(ManifestSynthesisService.class, 5000);
    assertNotNull(
        synthesis, "the embedded manifests-core bundle published ManifestSynthesisService");

    final ManifestSynthesisResult result =
        synthesis.synthesize(ManifestSynthesisRequest.ephemeral());
    assertNotNull(result, "synthesis returned a result (cdk8s setup ran on the staged jackson)");
  }
}
