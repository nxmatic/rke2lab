package io.nxmatic.rke2lab.cluster.bdd;

import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;

/**
 * The cluster domain's in-container execution front-door — the entry point a driver calls
 * reflectively THROUGH this bundle's classloader (a bundle shares its own loader), so the launcher
 * runs INSIDE Felix and the scenario resolves its collaborators from the registry. The in-container
 * twin of the way {@code ClusterSeedTopic} plays the seed host-side: {@link JUnitLauncherCore} with
 * an explicit {@code selectClass} of the {@code *Scenario} (NOT the {@code *Test} enumeration of
 * {@code InContainerJUnitRunner}, which is for a domain's own tests).
 *
 * <p>It returns the played model as a SERIALIZED JSON String, never the live {@code ReportModel}: a
 * jGiven object is loaded by this bundle's classloader and cannot cross the realm boundary to a
 * caller on the flat host loader (a {@code ClassCastException} — {@code ReportModel} is a different
 * class per loader). The String is the seam currency — the same shape the cross-world graft lifts
 * into the host runbook (host-side {@code ScenarioJsonReader} rebuilds the model in ITS realm).
 * This is the {@code ScenarioModel} membrane crossing the design mandates, in its minimal form.
 *
 * <p>Invoked through the bundle loader, so this class's loader IS the bundle's — the {@code
 * BundleReference} the launcher binds the worker thread to, and the loader the Jupiter engine
 * (which drives the jGiven scenario) instantiates through.
 */
public final class ClusterBddScenarios {

  private ClusterBddScenarios() {}

  /**
   * Play {@link ClusterReadinessScenario} in-container and return its finished model serialized to
   * JSON (the realm-crossing currency). The collaborators (the {@link
   * io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact}) are resolved by the scenario from
   * this bundle's registry — a caller seeds a mock before invoking, or the live edge published one.
   */
  public static String run() throws InterruptedException {
    return new JUnitLauncherCore<String>()
        .run(
            ClusterBddScenarios.class.getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(ClusterReadinessScenario.class)),
            (launcher, request) -> {
              launcher.execute(request);
              return new ScenarioJsonWriter(ClusterReadinessScenario.lastRunbook()).toString();
            });
  }
}
