package io.nxmatic.rke2lab.controlplane.osgi;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.junit.testkit.Osgi;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisService;
import io.nxmatic.rke2lab.manifests.port.ManifestUpdateGate;
import io.nxmatic.rke2lab.osgi.runtime.OsgiRuntime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * WI-C packaging proof: boot the framework from the bundles EMBEDDED in this artifact under {@code
 * META-INF/bundles/} — the exact deployed topology — rather than from classpath-located reactor
 * jars (the path {@link HostSeamEmbeddedFelixTest} exercises). The {@code stage-embedded-bundles}
 * dependency-plugin execution copies the jars into {@code generated-resources}, a resource root, so
 * they are on the test classpath here just as they are inside the deployed exec-jar; {@code
 * OsgiRuntime.embedded*} streams each straight into Felix.
 *
 * <p>This closes the gap a {@code jar tf} check leaves open: it proves the embedded jars actually
 * boot, resolve, and publish their services — not merely that they are present in the archive.
 */
@Osgi
class EmbeddedBundlesBootTest {

  private static OsgiRuntime runtime;

  @BeforeAll
  static void bootFromEmbeddedBundles() throws Exception {
    assertTrue(
        OsgiRuntime.hasEmbeddedBundles(),
        "the stage-embedded-bundles execution must have placed the jars under META-INF/bundles");
    runtime = OsgiRuntime.embeddedBootStack().embeddedBundle("manifests-core.jar").build().boot();
  }

  @AfterAll
  static void stopFelix() {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  void embeddedManifestsCorePublishesItsServicesTyped() {
    assertNotNull(
        runtime.awaitService(ManifestSynthesisService.class, 5000),
        "the embedded manifests-core bundle booted and SCR published ManifestSynthesisService"
            + " (its @Reference Resolver bound from the embedded felix.resolver)");
    assertNotNull(
        runtime.awaitService(ManifestUpdateGate.class, 5000),
        "the embedded manifests-core bundle published ManifestUpdateGate");
  }
}
