package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.junit.testkit.OsgiWorld;
import io.seedmatic.rke2lab.osgi.boot.discovery.BootPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;

/**
 * Pins the lever (spec Figure 4): it moves the SINGLE global start-level cursor synchronously, and
 * descent is TRANSIENT — a level-4 bundle stops when the cursor drops below 4 yet stays
 * persistently-started, so re-ascent re-lights it with no explicit start.
 *
 * <p>The testkit boots bundles at Felix's default level (1), NOT via the {@code BootPlanner} (which
 * assigns role→level only in the prod/exec boot). So this test reproduces what the planner does in
 * prod — pin felix.scr (a real, activatable bundle) to {@code START_LEVEL_BUNDLES} and raise the
 * cursor there — then exercises the lever over that. The lever never reassigns a bundle's level
 * (the planner owns that via {@code BundleStartLevel}); it only moves the framework cursor.
 */
@OsgiWorld
class StartLevelLeverTest {

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder().withScr().build();

  private FrameworkStartLevel cursor;
  private Bundle scr;
  private StartLevelLever lever;

  @BeforeEach
  void pinScrAtLevelFourAndClimb() throws InterruptedException {
    cursor = felix.context().getBundle(0).adapt(FrameworkStartLevel.class);
    scr = bundleBySymbolicName("org.apache.felix.scr");
    // What the BootPlanner does in prod: a domain-role bundle sits at START_LEVEL_BUNDLES.
    scr.adapt(BundleStartLevel.class).setStartLevel(BootPlan.START_LEVEL_BUNDLES);

    lever = new StartLevelLever(felix.context());
    lever.raiseTo(BootPlan.START_LEVEL_BUNDLES);
    assertEquals(BootPlan.START_LEVEL_BUNDLES, lever.current());
    assertEquals(
        Bundle.ACTIVE, scr.getState(), "the level-4 bundle is active once the cursor is at 4");
  }

  @Test
  void descendStopsTheLevelFourBundleTransientlyThenReAscentReLightsIt()
      throws InterruptedException {
    lever.descendTo(BootPlan.START_LEVEL_FRAMEWORK_RUNTIME);

    assertEquals(BootPlan.START_LEVEL_FRAMEWORK_RUNTIME, lever.current());
    assertNotEquals(Bundle.ACTIVE, scr.getState(), "below its level, the bundle is stopped");
    assertTrue(
        scr.adapt(BundleStartLevel.class).isPersistentlyStarted(),
        "the stop is TRANSIENT — persistent autostart is untouched");

    lever.raiseTo(BootPlan.START_LEVEL_BUNDLES);

    assertEquals(BootPlan.START_LEVEL_BUNDLES, lever.current());
    assertEquals(
        Bundle.ACTIVE, scr.getState(), "re-ascent re-lights it from autostart — no explicit start");
  }

  @Test
  void theMoveIsSynchronous() throws InterruptedException {
    lever.descendTo(BootPlan.START_LEVEL_FRAMEWORK_RUNTIME);
    // descendTo returned only after STARTLEVEL_CHANGED — so the cursor is already settled here.
    assertEquals(BootPlan.START_LEVEL_FRAMEWORK_RUNTIME, cursor.getStartLevel());
    assertFalse(
        scr.getState() == Bundle.ACTIVE, "the world has already settled when descendTo returns");
  }

  /** felix.scr is installed by withScr() but not registered in the testkit's named map. */
  private static Bundle bundleBySymbolicName(String symbolicName) {
    for (Bundle bundle : felix.context().getBundles()) {
      if (symbolicName.equals(bundle.getSymbolicName())) {
        return bundle;
      }
    }
    throw new IllegalStateException("no bundle installed for " + symbolicName);
  }
}
