package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;

/**
 * How a {@link JUnitLauncherCore} run turns the execution into its result — the second half the
 * core injects (the first is {@link DiscoveryStrategy}). It registers whatever listeners it needs
 * on the {@code launcher}, executes the {@code request}, and returns the harvested value.
 *
 * <p>The two implementations that motivate the seam: the in-container envelope harvests PASS/FAIL
 * lines from a {@code TestExecutionListener}; a runtime pipeline harvests the jGiven {@code
 * ReportModel} (the runbook).
 *
 * @param <R> the harvested result type — {@code List<String>} for the envelope, a {@code
 *     ReportModel} for a pipeline
 */
@FunctionalInterface
public interface HarvestStrategy<R> {

  R harvest(Launcher launcher, LauncherDiscoveryRequest request);
}
