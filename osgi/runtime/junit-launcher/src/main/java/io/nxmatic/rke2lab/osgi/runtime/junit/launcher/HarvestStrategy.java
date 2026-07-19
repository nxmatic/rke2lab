package io.nxmatic.rke2lab.osgi.runtime.junit.launcher;

import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;

/**
 * How a {@link JUnitLauncherCore} run turns the execution into its result — the second half the
 * core injects (the first is {@link DiscoveryStrategy}). It registers whatever listeners it needs
 * on the {@code launcher}, executes the {@code request}, and reads its harvest — including, for a
 * scenario run, the {@code ScenarioOutcome} the {@code ScenarioOutcomeExtension} seeded into the
 * {@code sessionStore} (the launcher session's own store, the OUTBOUND channel's read side). Those
 * scenario types live in {@code scenario-engine} (the jGiven side); this generic seam names none of
 * them — it is jGiven-free, which is why it lives in the runner bundle.
 *
 * <p>The two implementations that motivate the seam: the in-container envelope harvests PASS/FAIL
 * lines from a {@code TestExecutionListener}; a scenario front-door reads the {@code
 * ScenarioOutcome} back from the session store (via {@code ScenarioOutcomeSeed#read}) and returns
 * the runbook JSON + consultations. The runbook is NOT rethrown on a failed run — jGiven's {@code
 * ReportModel} is the fail-at-end collector, so the harvest returns the FAILED runbook and the
 * driver renders it, then inspects its execution status.
 *
 * @param <R> the harvested result type — {@code List<String>} for the envelope, the runbook JSON
 *     for a scenario front-door
 */
@FunctionalInterface
public interface HarvestStrategy<R> {

  R harvest(
      Launcher launcher,
      LauncherDiscoveryRequest request,
      NamespacedHierarchicalStore<Namespace> sessionStore);
}
