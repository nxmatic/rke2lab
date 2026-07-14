package io.nxmatic.rke2lab.seed.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.ScenarioGraft;
import io.nxmatic.rke2lab.seed.bdd.sow.Gardening;
import java.util.Map;

/**
 * The gardening gesture behind every crossing step: sow a soil's runbook through the open
 * gardening, and graft the reaped scion under this step. It COMPOSES the two halves of the
 * crossing, which live in different realms by design:
 *
 * <ul>
 *   <li>SOW — {@link Gardening#sow} (package {@code seed.bdd.sow}, EXPORTED): realm-agnostic, it
 *       grows {@code soil}'s scenario through the gardener and reaps the runbook JSON. The driver
 *       holds the open gardening today; a scion-peer could hold one in-container tomorrow (remote).
 *   <li>GRAFT — {@link ScenarioGraft} (host-flat): builds a host-realm {@link ReportModel} from
 *       that JSON and grafts the scion's steps under the root step named for this crossing,
 *       propagating the scion verdict (fail-fast across the frontier). The graft is LOCAL to
 *       whoever owns the runbook tree, so it stays in this non-exported base package.
 * </ul>
 *
 * <p>The root scenario passes its OWN live {@link ReportModel} ({@code getScenario().getModel()})
 * as {@code hostTree} — the single trunk every caller grafts into, read top-down by the operator.
 * The collaborators (the open {@link Gardening}, the host tree, the soil) are handed in through a
 * {@link Hidden} step, the way a domain scenario receives its contact — no injection machinery. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ playing a scion scenario is a sow).
 */
public class SowAndGraftStage extends Stage<SowAndGraftStage> {

  private final ScenarioGraft graft = new ScenarioGraft();

  @ScenarioState private String soil;
  @ScenarioState private Gardening gardening;
  @ScenarioState private ReportModel hostTree;
  @ScenarioState private Map<String, String> amendments;

  /**
   * Hand in the crossing's collaborators: the {@code soil} name to sow toward, the open {@link
   * Gardening}, and the root scenario's own {@link ReportModel} to graft into. Hidden — it carries
   * wiring, not narration. The crossing sows with no amendment (the scion falls back to its own
   * defaults); a crossing that must fill an amendment role uses {@link #sowing(String, Gardening,
   * ReportModel, Map)}.
   */
  @Hidden
  public SowAndGraftStage sowing(String soil, Gardening gardening, ReportModel hostTree) {
    return sowing(soil, gardening, hostTree, Map.of());
  }

  /**
   * The amending variant: {@code amendments} is a {@code {role → value}} map the host holds under
   * neutral {@link io.nxmatic.rke2lab.seed.broker.port.Amendment} roles (e.g. the incus crossing
   * fills {@code soil} with the plot to materialise into). Empty behaves exactly like {@link
   * #sowing(String, Gardening, ReportModel)}.
   */
  @Hidden
  public SowAndGraftStage sowing(
      String soil, Gardening gardening, ReportModel hostTree, Map<String, String> amendments) {
    this.soil = soil;
    this.gardening = gardening;
    this.hostTree = hostTree;
    this.amendments = amendments;
    return self();
  }

  /**
   * Sow the soil's runbook through the gardening and graft the reaped scion under {@code
   * rootStepName} — the name of the root step that stands for this crossing.
   */
  public SowAndGraftStage the_scion_is_sown_and_grafted(@Hidden String rootStepName) {
    final String runbookJson = gardening.sow(soil, amendments);
    graft.graftUnder(hostTree, rootStepName, graft.rebuild(runbookJson));
    return self();
  }
}
