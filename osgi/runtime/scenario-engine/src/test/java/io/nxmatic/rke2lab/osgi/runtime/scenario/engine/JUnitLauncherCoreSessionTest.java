package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.osgi.runtime.junit.launcher.JUnitLauncherCore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.store.Namespace;

/**
 * The session-store seam: a value the caller puts into the session-level store before execute() is
 * visible to a harvest that reads it back — proving the channel host-facts will ride (no
 * ThreadLocal).
 */
class JUnitLauncherCoreSessionTest {

  static final Namespace NS = Namespace.create("launcher-core-session-test");

  @Test
  void a_value_seeded_into_the_session_store_is_readable_by_the_harvest() throws Exception {
    final String seeded =
        new JUnitLauncherCore<String>()
            .run(
                getClass().getClassLoader(),
                JupiterTestEngine.class,
                wiring -> List.of(DiscoverySelectors.selectClass(EmptyProbe.class)),
                (launcher, request, sessionStore) -> {
                  launcher.execute(request);
                  // The harvest reads back what the seed put — the same session store both ends
                  // address, the mechanism the outbound ScenarioOutcome channel rides.
                  return sessionStore.get(NS, "fact", String.class);
                },
                store -> store.put(NS, "fact", "seeded-value"));
    assertEquals("seeded-value", seeded);
  }

  /** A trivial discovery target so the launcher has something to run. */
  static class EmptyProbe {
    @Test
    void noop() {}
  }
}
