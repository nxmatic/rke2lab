package io.nxmatic.rke2lab.controlplane.osgi;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.nxmatic.rke2lab.junit.testkit.Osgi;
import io.nxmatic.rke2lab.manifests.port.FloxRuntimeAssetService;
import io.nxmatic.rke2lab.manifests.port.ManifestDocumentService;
import io.nxmatic.rke2lab.manifests.port.ManifestExplodeService;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisService;
import io.nxmatic.rke2lab.manifests.port.ManifestUpdateGate;
import io.nxmatic.rke2lab.manifests.port.node.NodeEnvOverlayService;
import io.nxmatic.rke2lab.osgi.boot.discovery.BootStackJar;
import io.nxmatic.rke2lab.osgi.boot.discovery.BundleIndex;
import io.nxmatic.rke2lab.osgi.boot.discovery.EmbedCapability;
import io.nxmatic.rke2lab.osgi.runtime.OsgiRuntime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Milestone A de-risk proof: the host seam reads the manifests-world services from a real embedded
 * Felix booted by {@link OsgiRuntime} — the production boot component, not the test-only extension.
 *
 * <p>The proof is TYPED: every service is resolved as its {@code -port} interface and cast. That
 * only works if the host and the installed manifests-core bundle share ONE copy of each port class
 * — the single-exporter trick OsgiRuntime applies by mirroring manifests-core's bnd Import-Package
 * onto the system bundle. A split (two class copies) would surface here as {@code awaitService}
 * returning null or a {@code ClassCastException}, so this fails loudly before any exec-jar
 * packaging is touched.
 *
 * <p>manifests-core is located on the reactor classpath (an exploded {@code target/classes} dir
 * during {@code -am} builds); in the deployed exec-jar it will instead be an embedded intact jar,
 * but the boot path through {@link OsgiRuntime} is identical.
 */
@Osgi
class HostSeamEmbeddedFelixTest {

  private static OsgiRuntime runtime;

  @BeforeAll
  static void bootFelix() throws Exception {
    final BundleIndex classpath = BundleIndex.ofClasspath();
    final OsgiRuntime.Builder builder =
        OsgiRuntime.builder()
            .withPaxLogging(
                classpath.locateBySymbolicName(BootStackJar.PAX_LOGGING_API.symbolicName()),
                classpath.locateBySymbolicName(BootStackJar.PAX_LOGGING_LOGBACK.symbolicName()))
            .withScr()
            .runtimeBundle(classpath.locateBySymbolicName(BootStackJar.FELIX_SCR.symbolicName()))
            // felix.resolver's activator registers the org.osgi.service.resolver.Resolver service
            // that DefaultManifestSynthesisService binds via @Reference. With it absent SCR cannot
            // activate that component, so the seam services below never publish — its presence here
            // is what proves the SCR Resolver injection end to end.
            .runtimeBundle(
                classpath.locateBySymbolicName(BootStackJar.FELIX_RESOLVER.symbolicName()));

    // Install EVERY embeddable bundle the classpath carries (manifests-core, ssh-to-age-edge, …),
    // discovered by the embed capability — the same source-of-truth as the deployed exec-jar's
    // META-INF/bundles scan, just sourced from the reactor classpath. Naming them here would drift:
    // manifests-core's @Reference SshToAgeConverter is mandatory, so a forgotten edge would
    // silently
    // un-publish the seam services asserted below. The third-party boot stack above carries no
    // embed
    // capability, so it stays located by the BootStackJar registry — the irreducible remainder for
    // jars we don't own.
    classpath.matching(EmbedCapability.INSTALL_FILTER).forEach(builder::bundle);

    runtime = builder.build().boot();
  }

  @AfterAll
  static void stopFelix() {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  void hostResolvesTheThreeSeamServicesTyped() {
    assertNotNull(
        runtime.awaitService(ManifestSynthesisService.class, 5000),
        "SCR published ManifestSynthesisService, resolved typed across the seam");
    assertNotNull(
        runtime.awaitService(ManifestExplodeService.class, 5000),
        "SCR published ManifestExplodeService");
    assertNotNull(
        runtime.awaitService(ManifestUpdateGate.class, 5000),
        "SCR published ManifestUpdateGate (the R3-deferred gate)");
  }

  @Test
  void hostResolvesTheMilestoneBServicesTyped() {
    assertNotNull(
        runtime.awaitService(NodeEnvOverlayService.class, 5000),
        "SCR published NodeEnvOverlayService (couture 1)");
    assertNotNull(
        runtime.awaitService(ManifestDocumentService.class, 5000),
        "SCR published ManifestDocumentService (couture 2)");
    assertNotNull(
        runtime.awaitService(FloxRuntimeAssetService.class, 5000),
        "SCR published FloxRuntimeAssetService (couture 3)");
  }
}
