package io.nxmatic.rke2lab.netplan.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.junit.testkit.Osgi;
import io.nxmatic.rke2lab.netplan.api.NetplanSynthesisService;
import io.nxmatic.rke2lab.osgi.runtime.BootedFramework;
import io.nxmatic.rke2lab.osgi.runtime.FrameworkLaunchPipeline;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Generality proof for the 3rd entrypoint and a NEW embed-set: netplan-cli boots Felix from {@code
 * netplan-core.jar} staged under {@code META-INF/bundles/} — a model bundle seed-master never
 * embedded — and reads {@code NetplanSynthesisService} from the registry. This exercises {@code
 * BootPlanner}'s per-entrypoint {@code system.packages.extra} derivation on a fresh bundle set:
 * netplan-core's bnd imports (incl. {@code netplan.api}, now single-exported by netplan-port) must
 * resolve against the CLI's flat classpath, and the typed resolve below would fail loudly on any
 * split.
 */
@Osgi
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
  void embeddedNetplanCorePublishesSynthesisServiceTyped() {
    assertNotNull(
        framework.awaitService(NetplanSynthesisService.class, 5000),
        "the embedded netplan-core bundle booted and SCR published NetplanSynthesisService,"
            + " resolved typed across the seam (netplan.api single-exported by netplan-port)");
  }
}
