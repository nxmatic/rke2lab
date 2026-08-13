package io.seedmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.seedmatic.rke2lab.junit.testkit.OsgiWorld;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.StartLevelLever;
import io.seedmatic.rke2lab.osgibench.startlevel.GrowerCensus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.startlevel.BundleStartLevel;

/**
 * The start-level ordering proof, contributor-present-at-boot. Growers pinned at BUNDLES(4) and a
 * contributor pinned at level 5 are BOTH installed before the cursor climbs. Raising the cursor
 * activates the layers in order — growers at 4, then the contributor at 5 — and the contributor's
 * {@code @Reference(MULTIPLE)}, bound at ITS activation, must have observed EVERY grower. This is
 * the load-bearing guarantee BETA would lean on if it validated at BIND rather than at gather: a
 * level-5 contributor never binds into a half-active grower layer.
 */
@OsgiWorld
class ContributorAtBootBindsAllGrowersTest {

  private static final int BUNDLES_LEVEL = 4;
  private static final int CONTRIBUTOR_LEVEL = 5;
  private static final int GROWER_COUNT = 3;

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          .withScr()
          .systemPackages("io.seedmatic.rke2lab.osgibench.startlevel")
          .build();

  @Test
  void a_contributor_at_level_5_binds_every_grower_at_level_4() throws Exception {
    final List<Bundle> growers =
        felix.installMatching("(&(type=fixture)(suite=startlevel)(role=grower))");
    pin(growers, BUNDLES_LEVEL);
    felix.startAll(growers);

    final List<Bundle> contributor =
        felix.installMatching("(&(type=fixture)(suite=startlevel)(role=contributor))");
    pin(contributor, CONTRIBUTOR_LEVEL);
    felix.startAll(contributor);

    // Both layers are pinned but below the cursor (default 1); raising to 5 activates 4 then 5.
    new StartLevelLever(felix.context()).raiseTo(CONTRIBUTOR_LEVEL);

    final GrowerCensus census = felix.awaitService(GrowerCensus.class, 5000);
    assertNotNull(census, "the contributor activated once the cursor reached its level");
    assertEquals(
        GROWER_COUNT,
        census.growersObserved(),
        "the contributor at level 5 bound every grower already active at level 4 — no half-active layer");
  }

  private static void pin(List<Bundle> bundles, int level) {
    bundles.forEach(bundle -> bundle.adapt(BundleStartLevel.class).setStartLevel(level));
  }
}
