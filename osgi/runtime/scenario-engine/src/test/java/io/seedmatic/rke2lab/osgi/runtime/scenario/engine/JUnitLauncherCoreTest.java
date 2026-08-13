package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.JUnitLauncherCore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/**
 * Pins the neutral core: it runs one trivial green Jupiter test through INJECTED strategies (a
 * discovery that selects a nested class ignoring the — here absent — bundle wiring, a harvest that
 * counts the succeeded tests) and hands the harvested value back from its dedicated worker thread.
 * The two hardcoded halves of the old in-container runner are now the two SAMs; the three OSGi
 * crossings are the core's own.
 */
class JUnitLauncherCoreTest {

  /** A trivial green test the core discovers by class (host classpath — no bundle wiring). */
  static class TrivialGreenTest {
    @Test
    void passes() {}
  }

  @Test
  void runsOneGreenTestThroughInjectedStrategies() throws InterruptedException {
    final long succeeded =
        new JUnitLauncherCore<Long>()
            .run(
                getClass().getClassLoader(),
                JupiterTestEngine.class,
                wiring -> List.of(DiscoverySelectors.selectClass(TrivialGreenTest.class)),
                (launcher, request, sessionStore) -> {
                  final SummaryGeneratingListener listener = new SummaryGeneratingListener();
                  launcher.registerTestExecutionListeners(listener);
                  launcher.execute(request);
                  return listener.getSummary().getTestsSucceededCount();
                });

    assertEquals(1, succeeded);
  }
}
