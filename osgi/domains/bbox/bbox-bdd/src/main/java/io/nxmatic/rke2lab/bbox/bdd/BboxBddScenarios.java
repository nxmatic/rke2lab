package io.nxmatic.rke2lab.bbox.bdd;

import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;

/**
 * The bbox domain's in-container execution front-door — the entry point a driver calls reflectively
 * THROUGH this bundle's classloader (a bundle shares its own loader), so the launcher runs INSIDE
 * Felix and the scenario resolves its collaborators from the registry. The twin of {@code
 * ClusterBddScenarios}: {@link JUnitLauncherCore} with an explicit {@code selectClass} of the
 * {@code *Scenario}.
 *
 * <p>It returns EVERYTHING the run produced as ONE serialized JSON String — a {@link
 * RunbookEnvelope} of {@code (runbook, consultations)}. Neither can cross the realm boundary live:
 * the jGiven {@code ReportModel} is loaded by THIS bundle's loader (would {@code
 * ClassCastException} on the flat host loader), and a single reflective return forbids a
 * live-object-plus-json mix. So the whole envelope is JSON — the runbook as its {@code
 * ScenarioJsonWriter} text, the consultations as {@link SeedEnvelope}s (already flat 3-String
 * records). The host parses it with its own jackson.
 */
public final class BboxBddScenarios {

  private BboxBddScenarios() {}

  /**
   * The whole product of an in-container run, serialized as one JSON String across the realm
   * boundary: the {@code runbook} is the {@link ScenarioJsonWriter} text of the played {@code
   * ReportModel}; {@code consultations} are the doctor consultations the scenario raised on a
   * refused row (empty when every row reconciled). The host reads it back with its own jackson.
   */
  public record RunbookEnvelope(String runbook, List<SeedEnvelope> consultations) {}

  /**
   * Play {@link BboxReconciliationScenario} in-container and return its {@link RunbookEnvelope}
   * serialized to JSON. The collaborators (the {@link io.nxmatic.rke2lab.bbox.core.BboxReconciler},
   * the ambient {@link io.nxmatic.rke2lab.seed.broker.port.RunGate}, the doctor's {@code
   * ConsultingService} on a refused row) are resolved by the scenario from this bundle's registry —
   * a caller seeds mocks before invoking, or the live edge + the host's gate published them.
   */
  public static String run() throws InterruptedException {
    final SeedCodec codec = new SeedCodec();
    return new JUnitLauncherCore<String>()
        .run(
            BboxBddScenarios.class.getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(BboxReconciliationScenario.class)),
            (launcher, request) -> {
              launcher.execute(request);
              final String runbook =
                  new ScenarioJsonWriter(BboxReconciliationScenario.lastRunbook()).toString();
              return codec.encode(
                  new RunbookEnvelope(runbook, BboxReconciliationScenario.lastConsultations()));
            });
  }
}
