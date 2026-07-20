package io.nxmatic.rke2lab.manifests.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.manifests.contract.ManifestsRunbookInput;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcome;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import org.junit.jupiter.api.Test;

/**
 * The in-container proof of the manifests scion, run WHERE the scenario lives (this passenger
 * shares the manifests-bdd host loader through the fragment). Unlike the bbox proof — which mocks
 * every collaborator — the manifests scenario resolves the REAL synthesis: manifests-core's DS
 * {@code ManifestSynthesisService} activates under SCR and the scenario drives it (the env-config
 * synthesis, including the {@code PublishNodeEnvContributor}, runs inside it). It registers
 * NOTHING: manifests is a MODE-BLIND pure FS materialiser (no {@code Cultivating}/{@code Surveying}
 * pair, no run gate — it runs identically in both modes), so its only collaborator is that real SCR
 * component. It plays in-container through {@link ScenarioPlayer} (the shared play recipe the
 * production {@code GenericRunbookHandler} also drives) — seeding the activation facet through the
 * scenario's own inbound {@link ManifestSynthesisScenario#INPUT} channel, exactly as the handler
 * does — and asserts on the harvested {@link ScenarioOutcome}.
 *
 * <p>The assertion is the whole point of the chantier: given the operator's facet, the scenario's
 * WHEN stage — the transposition of {@code HostSlotManifest.Builder.policy()} — derives the policy
 * and synthesises (the publish env section among the ConfigMaps), and the runbook plays GREEN. That
 * is the control-plane policy genuinely reactivated INSIDE synthesis, proven end-to-end, not merely
 * compiled. It reads the LIVE outcome (same in-container worker), no JSON round-trip.
 */
public class ManifestSynthesisScenarioInContainerTest {

  @Test
  void the_scion_synthesizes_from_the_activation_facet() throws Exception {
    // The operator's usual posture (everything on except mesh, debug off) — a complete facet, the
    // same shape a sower plucks from Pulumi.dev.yaml.
    final ScenarioOutcome outcome = playWith(ManifestsRunbookInput.defaults());
    final ReportModel runbook = outcome.runbook();

    assertNotNull(runbook, "the player harvested the played model");
    assertEquals(1, runbook.getScenarios().size(), "one scenario played");
    assertEquals(
        ExecutionStatus.SUCCESS,
        runbook.getScenarios().get(0).getExecutionStatus(),
        "the facet was translated and synthesised (publish env section included) — plays green");
    // manifests only MATERIALISES content now; it no longer publishes a host-tree entry (incus owns
    // the host tree and publishes HostStagingEntry after grafting — § the I6 correction). So this
    // scion registers no Cellar and asserts nothing about the cellar.
  }

  /**
   * Play the scenario in-container through the shared {@link ScenarioPlayer} — seeding the facet
   * through the scenario's {@link ManifestSynthesisScenario#INPUT} channel — and return its live
   * {@link ScenarioOutcome}. Nothing is registered: the synthesis service is a real SCR component,
   * and the scenario is mode-blind (no run gate to publish).
   */
  private static ScenarioOutcome playWith(ManifestsRunbookInput facet) throws Exception {
    return new ScenarioPlayer()
        .play(ManifestSynthesisScenario.class, ManifestSynthesisScenario.INPUT.into(facet));
  }
}
