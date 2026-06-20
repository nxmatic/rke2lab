package io.nxmatic.rke2lab.manifests.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisService;
import io.nxmatic.rke2lab.osgi.runtime.OsgiRuntime;
import io.nxmatic.rke2lab.osgi.testkit.Osgi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Generality proof for the 2nd entrypoint: manifests-cli boots Felix from the bundles staged under
 * {@code META-INF/bundles/} — the exact topology {@code Main} uses at runtime — and reads {@code
 * ManifestSynthesisService} from the registry. This is the standalone-{@code main()} counterpart of
 * seed-master's {@code EmbeddedBundlesBootTest}; it proves the shared {@link
 * OsgiRuntime#embeddedBootStack()} seam carries to a CLI, fixing the off-framework ServiceLoader
 * bug (a null {@code Resolver} since the Resolver became an {@code @Reference}).
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
  void embeddedManifestsCorePublishesSynthesisServiceTyped() {
    assertNotNull(
        runtime.awaitService(ManifestSynthesisService.class, 5000),
        "the embedded manifests-core bundle booted and SCR published ManifestSynthesisService"
            + " (its @Reference Resolver bound from the embedded felix.resolver)");
  }
}
