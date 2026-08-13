package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.junit.testkit.OsgiWorld;
import io.seedmatic.rke2lab.osgi.boot.discovery.BootPlan;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.startlevel.BundleStartLevel;

/**
 * Pins the two disciplines (spec Figures 5 & 6): both pull the shared {@link BaseWorldExtension}
 * (connect + raiseTo(4)) and fork only in teardown. {@link IsolatedWorld} SAWTOOTHS — the method
 * scope descends at {@code @AfterEach} and re-ascends at {@code @BeforeEach}, so a level-4 bundle
 * is torn down and re-lit AROUND each case; {@link SeedRuntime} HOLDS — it climbs once and never
 * descends between methods, the bundle staying up across the whole run. The discriminating
 * observable is felix.scr's {@code STARTED}-event count.
 *
 * <p>The mirror of {@code InstanceDisciplineTest} for the extension world (specific→general naming:
 * {@code Extension}/{@code Instance} qualify the shared {@code Discipline}).
 *
 * <p>Topology dictated by JUnit ordering: a class-level discipline extension ({@code @ExtendWith}
 * via the annotation) runs its {@code beforeAll} BEFORE any field {@code @RegisterExtension}, so a
 * disciplined class cannot boot its own world in time. The disciplines are therefore
 * {@code @Nested} inside an outer {@link WorldFixture} that boots one real Felix and seeds a
 * NON-owning connection into the store; the nested disciplines' {@code
 * BaseWorldExtension.beforeAll} reads it via the store's ancestor walk. The fixture keeps the
 * teardown (ownsLifecycle=false) — no exec-jar staging.
 */
@OsgiWorld
class ExtensionDisciplineTest {

  static final AtomicInteger scrStartedCount = new AtomicInteger();

  /**
   * Boots one real Felix (guarded against per-nested-container re-entry), pins felix.scr to the
   * bundle level as the BootPlanner does in prod, counts its re-lights, and seeds a non-owning
   * connection into the store for the nested disciplines to read via the ancestor walk.
   */
  static final class WorldFixture implements BeforeAllCallback {
    private final OutOfContainerFrameworkExtension felix =
        OutOfContainerFrameworkExtension.builder().withScr().build();
    private boolean booted;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
      if (booted) {
        return; // re-entry for a nested container — the outer boot already seeded the store.
      }
      felix.beforeAll(context);
      booted = true;

      final Bundle scr = bundleBySymbolicName("org.apache.felix.scr");
      scr.adapt(BundleStartLevel.class).setStartLevel(BootPlan.START_LEVEL_BUNDLES);

      // Settle a DETERMINISTIC baseline before the disciplines run: descend below the bundle level
      // (scr stopped, synchronously) so no async start/stop from the boot is in flight, THEN start
      // counting. Every STARTED event after this is caused by a discipline's lever move — not by
      // the
      // boot's own late activation, which would otherwise race the first case's snapshot.
      new StartLevelLever(felix.context()).descendTo(BootPlan.START_LEVEL_FRAMEWORK_RUNTIME);
      // A SYNCHRONOUS listener: it counts scr's STARTED DURING the state change, inside
      // setStartLevel, BEFORE STARTLEVEL_CHANGED fires — so when the lever's raiseTo returns the
      // count is already settled. An async addBundleListener would deliver STARTED after the lever
      // returned, lagging the count by one method and racing the assertion.
      final SynchronousBundleListener counter =
          event -> {
            if (event.getBundle() == scr && event.getType() == BundleEvent.STARTED) {
              scrStartedCount.incrementAndGet();
            }
          };
      felix.context().addBundleListener(counter);
      context
          .getStore(BaseWorldExtension.NAMESPACE)
          .put(
              BaseWorldExtension.CONNECTION, OsgiConnection.over(felix.context(), false, () -> {}));
    }

    private Bundle bundleBySymbolicName(String symbolicName) {
      for (Bundle bundle : felix.context().getBundles()) {
        if (symbolicName.equals(bundle.getSymbolicName())) {
          return bundle;
        }
      }
      throw new IllegalStateException("no bundle installed for " + symbolicName);
    }
  }

  @RegisterExtension static final WorldFixture world = new WorldFixture();

  // Snapshots the re-light count as each first case runs — static, since JUnit gives each @Test
  // method a FRESH nested-class instance, so an instance field would not survive across methods.
  private static int isolationStartsAtCaseOne;
  private static int stationaryStartsAtPipelineOne;

  @Nested
  @IsolatedWorld
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class Isolation {
    @Test
    @Order(1)
    void caseOne() {
      isolationStartsAtCaseOne = scrStartedCount.get();
    }

    @Test
    @Order(2)
    void caseTwo() {
      // Between the two cases, @AfterEach descended (stopping scr) and @BeforeEach re-ascended
      // (re-lighting it) — so the STARTED count has RISEN: the sawtooth, each case clean.
      assertTrue(
          scrStartedCount.get() > isolationStartsAtCaseOne,
          "isolation re-lights the level-4 bundle between cases (sawtooth)");
    }
  }

  @Nested
  @SeedRuntime
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class Stationary {
    @Test
    @Order(1)
    void pipelineOne() {
      stationaryStartsAtPipelineOne = scrStartedCount.get();
    }

    @Test
    @Order(2)
    void pipelineTwo() {
      // No per-method move: the world is HELD at level 4 across pipelines, never re-lit.
      assertEquals(
          stationaryStartsAtPipelineOne,
          scrStartedCount.get(),
          "stationary holds the level-4 bundle across pipelines (no re-light)");
    }
  }
}
