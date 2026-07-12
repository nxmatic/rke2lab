package io.nxmatic.rke2lab.manifests.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisService;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.nxmatic.rke2lab.osgi.runtime.framework.FrameworkLaunch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Generality proof for the 2nd entrypoint: manifests-cli boots Felix from the bundles staged under
 * {@code META-INF/bundles/} — the exact topology {@code Main} uses at runtime — and reads {@code
 * ManifestSynthesisService} from the registry. It proves the shared {@link
 * FrameworkLaunch#embedded()} boot seam carries to a CLI, fixing the off-framework ServiceLoader
 * bug (a null {@code Resolver} since the Resolver became an {@code @Reference}).
 */
@OsgiWorld
class EmbeddedBundlesBootTest {

  private static BootedFramework framework;

  @BeforeAll
  static void bootFromEmbeddedBundles() {
    assertTrue(
        FrameworkLaunch.hasEmbeddedBundles(),
        "the stage-embedded-bundles execution must have placed the jars under META-INF/bundles");
    framework = FrameworkLaunch.embedded().launch();
  }

  @AfterAll
  static void stopFelix() {
    if (framework != null) {
      framework.close();
    }
  }

  @Test
  void embeddedManifestsCorePublishesSynthesisServiceTyped() {
    assertNotNull(
        framework.awaitService(ManifestSynthesisService.class, 5000),
        "the embedded manifests-core bundle booted and SCR published ManifestSynthesisService"
            + " (its @Reference Resolver bound from the embedded felix.resolver)");
  }
}
