package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.osgi.runtime.junit.launcher.JUnitLauncherCore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.StoreScope;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.store.Namespace;

/**
 * Reproduces the live NPE {@code no ScenarioOutcome in the session store}: the outbound channel
 * writes at {@link StoreScope#LAUNCHER_SESSION} scope in {@code afterTestExecution} and the harvest
 * reads it back off the session store {@code ScenarioPlayer} holds. It works when a scenario is
 * played ALONE (one launcher session — every {@code *BddInContainerTest}), but the host root plays
 * {@code ClusterSeedScenario} in an OUTER launcher session whose WHEN opens an INNER session per
 * sown scion — the exact shape below. If the inner extension's {@code LAUNCHER_SESSION} put does
 * not land in the inner {@code session.getStore()} the inner harvest reads, the nested read misses.
 */
class NestedLauncherSessionStoreTest {

  static final String NS_PART = NestedLauncherSessionStoreTest.class.getName();
  static final Namespace NS = Namespace.create(NS_PART);
  static final String KEY = "outcome";

  /** Baseline: a single (non-nested) session round-trips the LAUNCHER_SESSION-scoped value. */
  @Test
  void single_session_reads_back_the_launcher_session_scoped_value() throws Exception {
    assertEquals("seeded-by-after-callback", playInner());
  }

  /**
   * The live shape: the SAME inner play, but performed from INSIDE an outer launcher session (as
   * the host root's WHEN sows a scion inside its own run). Reproduces the nested-session store
   * miss.
   */
  @Test
  void nested_session_reads_back_the_launcher_session_scoped_value() throws Exception {
    final String innerResult =
        new JUnitLauncherCore<String>()
            .run(
                getClass().getClassLoader(),
                JupiterTestEngine.class,
                wiring -> List.of(DiscoverySelectors.selectClass(OuterProbe.class)),
                (launcher, request, sessionStore) -> {
                  launcher.execute(request);
                  // The outer harvest returns what the OUTER probe captured from its own INNER
                  // play.
                  return OuterProbe.innerResult;
                });
    assertEquals("seeded-by-after-callback", innerResult);
  }

  /** Runs the inner play (one launcher session) and returns what its harvest read back. */
  private static String playInner() throws Exception {
    return new JUnitLauncherCore<String>()
        .run(
            NestedLauncherSessionStoreTest.class.getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(InnerProbe.class)),
            (launcher, request, sessionStore) -> {
              launcher.execute(request);
              return sessionStore.get(NS, KEY, String.class);
            });
  }

  /** The inner scenario: an after-callback seeds the outcome at LAUNCHER_SESSION scope. */
  @ExtendWith(SeedingAfterCallback.class)
  static class InnerProbe {
    @Test
    void plays() {}
  }

  /**
   * The outer scenario: its body opens a nested inner play, exactly as the host WHEN sows a scion.
   */
  static class OuterProbe {
    static volatile String innerResult;

    @Test
    void sows_a_nested_scion() throws Exception {
      innerResult = playInner();
    }
  }

  /** The outbound-channel writer twin: put at LAUNCHER_SESSION scope after the test body. */
  static class SeedingAfterCallback implements AfterTestExecutionCallback {
    @Override
    public void afterTestExecution(ExtensionContext context) {
      final ExtensionContext.Namespace ns = ExtensionContext.Namespace.create(NS_PART);
      context.getStore(StoreScope.LAUNCHER_SESSION, ns).put(KEY, "seeded-by-after-callback");
    }
  }
}
