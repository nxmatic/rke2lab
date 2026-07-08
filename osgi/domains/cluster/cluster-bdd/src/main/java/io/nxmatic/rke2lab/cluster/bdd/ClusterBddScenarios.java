package io.nxmatic.rke2lab.cluster.bdd;

import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import io.nxmatic.rke2lab.world.gateway.codec.DocumentCodec;
import io.nxmatic.rke2lab.world.gateway.port.Document;
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
 * <p>It returns EVERYTHING the run produced as ONE serialized JSON String — an {@link
 * RunbookEnvelope} of {@code (runbook, consultations)}. Two things must cross and neither can cross
 * live: the jGiven {@code ReportModel} is loaded by THIS bundle's loader and would {@code
 * ClassCastException} on the flat host loader; and returning a single reflective value forbids a
 * live-object-plus-json mix. So the whole envelope is JSON — the runbook as its {@code
 * ScenarioJsonWriter} text, the consultations as {@link Document}s (already flat 3-String records
 * whose payload is itself JSON). The host parses the envelope with ITS OWN jackson — no jGiven or
 * jackson type ever crosses — and rebuilds the model + records the consultations in its realm. This
 * is the cross-world graft's membrane, in the form the design mandates.
 *
 * <p>Invoked through the bundle loader, so this class's loader IS the bundle's — the {@code
 * BundleReference} the launcher binds the worker thread to, and the loader the Jupiter engine
 * (which drives the jGiven scenario) instantiates through.
 */
public final class ClusterBddScenarios {

  private ClusterBddScenarios() {}

  /**
   * The whole product of an in-container run, serialized as one JSON String across the realm
   * boundary: the {@code runbook} is the {@link ScenarioJsonWriter} text of the played {@code
   * ReportModel}; {@code consultations} are the doctor consultations the scenario raised on a
   * failing phase (empty when every phase passed). The host reads it back with its own jackson.
   */
  public record RunbookEnvelope(String runbook, List<Document> consultations) {}

  /**
   * Play {@link ClusterReadinessScenario} in-container and return its {@link RunbookEnvelope}
   * serialized to JSON (the realm-crossing currency). The collaborators (the {@link
   * io.nxmatic.rke2lab.cluster.port.ClusterReadinessContact}, the doctor's {@code
   * ConsultingService} on a failing phase) are resolved by the scenario from this bundle's registry
   * — a caller seeds a mock before invoking, or the live edge published one.
   */
  public static String run() throws InterruptedException {
    final DocumentCodec codec = new DocumentCodec();
    return new JUnitLauncherCore<String>()
        .run(
            ClusterBddScenarios.class.getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(ClusterReadinessScenario.class)),
            (launcher, request) -> {
              launcher.execute(request);
              final String runbook =
                  new ScenarioJsonWriter(ClusterReadinessScenario.lastRunbook()).toString();
              return codec.encode(
                  new RunbookEnvelope(runbook, ClusterReadinessScenario.lastConsultations()));
            });
  }
}
