package io.nxmatic.rke2lab.systemd.bdd;

import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;

/**
 * The systemd domain's in-container execution front-door — the systemd twin of {@code
 * ClusterBddScenarios}. A driver calls {@link #run()} reflectively THROUGH this bundle's
 * classloader (a bundle shares its own loader), so the launcher runs INSIDE Felix and the scenario
 * resolves its collaborators from the registry. {@link JUnitLauncherCore} with an explicit {@code
 * selectClass} of {@link SystemdAdapterScenario} (NOT a {@code *Test} enumeration).
 *
 * <p>It returns EVERYTHING the run produced as ONE serialized JSON String — a {@link
 * RunbookEnvelope} of {@code (runbook, consultations)}. Two things must cross and neither can cross
 * live: the jGiven {@code ReportModel} is loaded by THIS bundle's loader and would {@code
 * ClassCastException} on the flat host loader; and returning a single reflective value forbids a
 * live-object-plus-json mix. So the whole envelope is JSON — the runbook as its {@code
 * ScenarioJsonWriter} text, the consultations as {@link SeedEnvelope}s (already flat 3-String
 * records whose payload is itself JSON). The host parses the envelope with ITS OWN jackson and
 * rebuilds the model + records the consultations in its realm. This is the cross-world graft's
 * membrane, in the form the design mandates.
 */
public final class SystemdBddScenarios {

  private SystemdBddScenarios() {}

  /**
   * The whole product of an in-container run, serialized as one JSON String across the realm
   * boundary: the {@code runbook} is the {@link ScenarioJsonWriter} text of the played {@code
   * ReportModel}; {@code consultations} are the doctor consultations the scenario raised on a
   * failing facet (empty when every facet passed). The host reads it back with its own jackson.
   */
  public record RunbookEnvelope(String runbook, List<SeedEnvelope> consultations) {}

  /**
   * Play {@link SystemdAdapterScenario} in-container and return its {@link RunbookEnvelope}
   * serialized to JSON (the realm-crossing currency). The collaborators (the {@link
   * io.nxmatic.rke2lab.systemd.port.SystemdRuntimeProbe}, the doctor's {@code ConsultingService} on
   * a failing facet) are resolved by the scenario from this bundle's registry — a caller seeds a
   * mock before invoking, or the live edge published one.
   */
  public static String run() throws InterruptedException {
    final SeedCodec codec = new SeedCodec();
    return new JUnitLauncherCore<String>()
        .run(
            SystemdBddScenarios.class.getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(SystemdAdapterScenario.class)),
            (launcher, request) -> {
              launcher.execute(request);
              final String runbook =
                  new ScenarioJsonWriter(SystemdAdapterScenario.lastRunbook()).toString();
              return codec.encode(
                  new RunbookEnvelope(runbook, SystemdAdapterScenario.lastConsultations()));
            });
  }
}
