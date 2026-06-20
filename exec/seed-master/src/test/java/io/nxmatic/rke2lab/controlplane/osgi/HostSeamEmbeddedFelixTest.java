package io.nxmatic.rke2lab.controlplane.osgi;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.nxmatic.rke2lab.manifests.port.FloxRuntimeAssetService;
import io.nxmatic.rke2lab.manifests.port.ManifestDocumentService;
import io.nxmatic.rke2lab.manifests.port.ManifestExplodeService;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisService;
import io.nxmatic.rke2lab.manifests.port.ManifestUpdateGate;
import io.nxmatic.rke2lab.manifests.port.node.NodeEnvOverlayService;
import io.nxmatic.rke2lab.osgi.runtime.OsgiRuntime;
import io.nxmatic.rke2lab.osgi.testkit.Osgi;
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
    runtime =
        OsgiRuntime.builder()
            .withPaxLogging(
                OsgiRuntime.locateOnClasspath("pax-logging-api"),
                OsgiRuntime.locateOnClasspath("pax-logging-logback"))
            .withScr()
            .runtimeJar(OsgiRuntime.locateOnClasspath("org.apache.felix.scr"))
            // felix.resolver's activator registers the org.osgi.service.resolver.Resolver service
            // that DefaultManifestSynthesisService binds via @Reference. With it absent SCR cannot
            // activate that component, so the seam services below never publish — its presence here
            // is what proves the SCR Resolver injection end to end.
            .runtimeJar(OsgiRuntime.locateOnClasspath("org.apache.felix.resolver"))
            .bundle(OsgiRuntime.locateOnClasspath("manifests-core"))
            .build()
            .boot();
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
