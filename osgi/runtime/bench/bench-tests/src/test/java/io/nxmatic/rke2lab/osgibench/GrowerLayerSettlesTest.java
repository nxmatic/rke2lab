package io.nxmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.StartLevelLever;
import io.nxmatic.rke2lab.osgibench.startlevel.Grower;
import io.nxmatic.rke2lab.osgibench.startlevel.GrowerCensus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.startlevel.BundleStartLevel;

/**
 * The start-level ordering proof, growers-first. Growers are pinned at the BUNDLES level (4); the
 * framework cursor is then raised PAST them to the contributor level (5) with NO contributor
 * present — the "open the contributor layer unconditionally" target. Two things are asserted:
 *
 * <ul>
 *   <li><b>The grower layer settles.</b> Reaching level 5 means the level-4 layer is not merely
 *       STARTED but fully SCR-ACTIVE — every grower service is in the registry. This is the
 *       started-vs-activated question at the layer boundary.
 *   <li><b>Runtime discovery is non-blocking.</b> With the cursor already parked at 5, a
 *       contributor installed at RUNTIME activates and its {@code @Reference(MULTIPLE)} binds EVERY
 *       grower — the future bundle-native contributor arriving late into a settled grower world.
 * </ul>
 */
@OsgiWorld
class GrowerLayerSettlesTest {

  private static final int BUNDLES_LEVEL = 4;
  private static final int CONTRIBUTOR_LEVEL = 5;
  private static final int GROWER_COUNT = 3;

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          .withScr()
          .systemPackages("io.nxmatic.rke2lab.osgibench.startlevel")
          .build();

  @Test
  void the_grower_layer_is_active_at_level_5_then_a_runtime_contributor_binds_them_all()
      throws Exception {
    final List<Bundle> growers =
        felix.installMatching("(&(type=fixture)(suite=startlevel)(role=grower))");
    pin(growers, BUNDLES_LEVEL);
    felix.startAll(growers);

    final StartLevelLever cursor = new StartLevelLever(felix.context());
    cursor.raiseTo(CONTRIBUTOR_LEVEL);

    // PROOF 1 — reaching the (empty) contributor level means the grower level below is fully
    // active.
    assertEquals(
        GROWER_COUNT,
        awaitGrowerCount(GROWER_COUNT),
        "every grower is SCR-ACTIVE once the cursor reaches the contributor level above them");

    // PROOF 3 — a contributor installed at RUNTIME, cursor already parked at 5, binds them all.
    final List<Bundle> contributor =
        felix.installMatching("(&(type=fixture)(suite=startlevel)(role=contributor))");
    pin(contributor, CONTRIBUTOR_LEVEL);
    felix.startAll(contributor);

    final GrowerCensus census = felix.awaitService(GrowerCensus.class, 5000);
    assertNotNull(
        census, "the runtime-installed contributor activated with the cursor at its level");
    assertEquals(
        GROWER_COUNT,
        census.growersObserved(),
        "the late contributor bound every grower already active below it");
  }

  private static void pin(List<Bundle> bundles, int level) {
    bundles.forEach(bundle -> bundle.adapt(BundleStartLevel.class).setStartLevel(level));
  }

  /**
   * Poll the registry until the grower count reaches {@code expected} or a short budget elapses.
   */
  private static int awaitGrowerCount(int expected) throws Exception {
    final long deadline = System.currentTimeMillis() + 5000;
    int count = growerCount();
    while (count < expected && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
      count = growerCount();
    }
    return count;
  }

  private static int growerCount() throws Exception {
    return felix.context().getServiceReferences(Grower.class, null).size();
  }
}
