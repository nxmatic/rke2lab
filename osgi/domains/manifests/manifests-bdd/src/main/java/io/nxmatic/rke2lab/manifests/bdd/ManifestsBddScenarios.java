package io.nxmatic.rke2lab.manifests.bdd;

import com.tngtech.jgiven.report.json.ScenarioJsonWriter;
import io.nxmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.CellarEntriesSeed;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.JUnitLauncherCore;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;

/**
 * The manifests domain's in-container execution front-door — the entry point the runbook handler
 * calls THROUGH this bundle's classloader, so the launcher runs INSIDE Felix and the scenario
 * resolves its collaborators (the synthesis + overlay services, the RunGate) from the registry. The
 * twin of {@code BboxBddScenarios}: {@link JUnitLauncherCore} with an explicit {@code selectClass}
 * of {@link ManifestSynthesisScenario}.
 *
 * <p>THE one difference from bbox's front-door: it takes the activation facet — bbox ignores its
 * trigger, this one seeds the sown {@link ManifestsRunbookInput} into the scenario ({@link
 * ManifestSynthesisScenario#seedInput}) before the launcher selects it, so the WHEN stage
 * translates the operator's choice. It returns a {@link RunbookEnvelope} of {@code (runbook,
 * consultations)} as one serialized JSON String; consultations are always empty (the manifests
 * scion consults no one — a synthesis failure is a build defect surfaced as a failed step, not a
 * symptom).
 */
public final class ManifestsBddScenarios {

  private ManifestsBddScenarios() {}

  /**
   * The whole product of an in-container run, serialized as one JSON String across the realm
   * boundary: the {@code runbook} is the {@link ScenarioJsonWriter} text of the played {@code
   * ReportModel}; {@code consultations} is always empty (the manifests scion consults no doctor).
   */
  public record RunbookEnvelope(String runbook, List<SeedEnvelope> consultations) {}

  /**
   * Play {@link ManifestSynthesisScenario} in-container with the given activation facet and return
   * its {@link RunbookEnvelope} serialized to JSON. The facet is seeded into the scenario before
   * the launcher selects it; the collaborators (the synthesis + overlay services, the ambient
   * RunGate) are resolved by the scenario from this bundle's registry.
   */
  public static String run(
      ManifestsRunbookInput facet, Optional<String> txId, List<String> inheritedEntries)
      throws InterruptedException {
    ManifestSynthesisScenario.seedInput(facet);
    final SeedCodec codec = new SeedCodec();
    return new JUnitLauncherCore<String>()
        .run(
            ManifestsBddScenarios.class.getClassLoader(),
            JupiterTestEngine.class,
            wiring -> List.of(DiscoverySelectors.selectClass(ManifestSynthesisScenario.class)),
            (launcher, request) -> {
              launcher.execute(request);
              final String runbook =
                  new ScenarioJsonWriter(ManifestSynthesisScenario.lastRunbook()).toString();
              return codec.encode(new RunbookEnvelope(runbook, List.of()));
            },
            txId.map(TxIdSeed::into)
                .orElse(store -> {})
                .andThen(CellarEntriesSeed.into(inheritedEntries)));
  }
}
