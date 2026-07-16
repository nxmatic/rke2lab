package io.nxmatic.rke2lab.incus.bdd;

import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import io.nxmatic.rke2lab.incus.contract.IncusRunbookInput;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;

/**
 * The incus domain's in-container execution front-door — the entry point a driver calls
 * reflectively THROUGH this bundle's classloader (a bundle shares its own loader), so the launcher
 * runs INSIDE Felix and the scenario resolves its collaborators from the registry. The twin of
 * {@code BboxBddScenarios}: {@link JUnitLauncherCore} with an explicit {@code selectClass} of
 * {@link IncusProvisionScenario}.
 *
 * <p>It returns EVERYTHING the run produced as ONE serialized JSON String — a {@link
 * RunbookEnvelope} of {@code (runbook, consultations)}. Neither can cross the realm boundary live:
 * the jGiven {@code ReportModel} is loaded by THIS bundle's loader (would {@code
 * ClassCastException} on the flat host loader), and a single reflective return forbids a
 * live-object-plus-json mix. So the whole envelope is JSON — the runbook as its {@code
 * ScenarioJsonWriter} text, the consultations as {@link SeedEnvelope}s (already flat 3-String
 * records). The host parses it with its own jackson.
 */
public final class IncusBddScenarios {

  private IncusBddScenarios() {}

  /**
   * The whole product of an in-container run, serialized as one JSON String across the realm
   * boundary: the {@code runbook} is the {@link ScenarioJsonWriter} text of the played {@code
   * ReportModel}; {@code consultations} are the doctor consultations the scenario raised on a
   * build/exec failure (empty when the provision was clean). The host reads it back with its own
   * jackson.
   */
  public record RunbookEnvelope(String runbook, List<SeedEnvelope> consultations) {}

  /**
   * Play {@link IncusProvisionScenario} in-container with the given activation input and return its
   * {@link RunbookEnvelope} serialized to JSON. The input (carrying the {@code @Amendment(SOIL)}
   * the scenario forwards to the manifests scion it consults) is seeded before the launcher selects
   * the class. The collaborators (the {@link io.nxmatic.rke2lab.incus.contract.ImageBuilder}, the
   * {@link io.nxmatic.rke2lab.incus.contract.IncusInstanceContact}, the ambient {@link
   * io.nxmatic.rke2lab.seed.broker.port.RunGate}, the doctor's {@code ConsultingService} on
   * failure) are resolved by the scenario from this bundle's registry — a caller seeds mocks before
   * invoking, or the live edges + the host's gate published them.
   */
  public static String run(IncusRunbookInput input, Optional<String> txId)
      throws InterruptedException {
    IncusProvisionScenario.seedInput(input);
    final SeedCodec codec = new SeedCodec();
    return new JUnitLauncherCore<String>()
        .run(
            IncusBddScenarios.class.getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(IncusProvisionScenario.class)),
            (launcher, request) -> {
              launcher.execute(request);
              final String runbook =
                  new ScenarioJsonWriter(IncusProvisionScenario.lastRunbook()).toString();
              return codec.encode(
                  new RunbookEnvelope(runbook, IncusProvisionScenario.lastConsultations()));
            },
            txId.map(TxIdSeed::into).orElse(store -> {}));
  }
}
