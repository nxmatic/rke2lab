package io.nxmatic.rke2lab.seed.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.ScenarioGraft;
import io.nxmatic.rke2lab.seed.bdd.sow.RunbookSower;
import io.nxmatic.rke2lab.seed.broker.port.SeedBroker;

/**
 * The gardening gesture behind every non-cellar root step: sow a soil's runbook coordinate through
 * the ONE door, and graft the reaped scion under this step. It COMPOSES the two halves of the
 * crossing, which live in different realms by design:
 *
 * <ul>
 *   <li>SOW — {@link RunbookSower} (package {@code seed.bdd.sow}, EXPORTED): realm-agnostic, it
 *       sows {@code RunbookCoordinate(soil)} and reaps the runbook JSON. The host sows today; a
 *       scion could sow in-container tomorrow (remote), through the same door.
 *   <li>GRAFT — {@link ScenarioGraft} (host-flat): builds a host-realm {@link ReportModel} from
 *       that JSON and grafts the scion's steps under the root step named for this crossing,
 *       propagating the scion verdict (fail-fast across the frontier). The graft is LOCAL to
 *       whoever owns the runbook tree, so it stays in this non-exported base package.
 * </ul>
 *
 * <p>The root scenario passes its OWN live {@link ReportModel} ({@code getScenario().getModel()})
 * as {@code hostTree} — the single trunk every caller grafts into, read top-down by the operator.
 * No injection machinery: the collaborators (broker, host tree, soil) are handed in through a
 * {@link Hidden} step, the way a domain scenario receives its contact. See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ playing a scion scenario is a sow).
 */
public class SowAndGraftStage extends Stage<SowAndGraftStage> {

  private final ScenarioGraft graft = new ScenarioGraft();

  @ScenarioState private String soil;
  @ScenarioState private SeedBroker broker;
  @ScenarioState private ReportModel hostTree;

  /**
   * Hand in the crossing's collaborators: the {@code soil} name to sow toward, the {@link
   * SeedBroker} door, and the root scenario's own {@link ReportModel} to graft into. Hidden — it
   * carries wiring, not narration.
   */
  @Hidden
  public SowAndGraftStage sowing(String soil, SeedBroker broker, ReportModel hostTree) {
    this.soil = soil;
    this.broker = broker;
    this.hostTree = hostTree;
    return self();
  }

  /**
   * Sow the soil's runbook coordinate and graft the reaped scion under {@code rootStepName} — the
   * name of the root step that stands for this crossing.
   */
  public SowAndGraftStage the_scion_is_sown_and_grafted(@Hidden String rootStepName) {
    final String runbookJson = new RunbookSower(broker).sowRunbook(soil);
    graft.graftUnder(hostTree, rootStepName, graft.rebuild(runbookJson));
    return self();
  }
}
