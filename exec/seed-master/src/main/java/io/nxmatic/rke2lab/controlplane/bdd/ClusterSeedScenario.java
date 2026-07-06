package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The composing scenario that replaces {@code ClusterSeedPipeline}. Extends {@code
 * ScenarioTestBase} (NOT {@code ScenarioTest}) so we declare the extensions ourselves in order:
 * {@link HostSeeder} FIRST (populates host-facts AND hands jGiven the driver's ReportModel before
 * jGiven initializes the scenario), then {@code JGivenExtension}. No {@code Test} suffix on the
 * class → invisible to surefire; played only via the launcher by the driver.
 *
 * <p>The runbook is NOT harvested back: the driver seeds its OWN {@code ReportModel} into the
 * session store, {@link HostSeeder} plants it in jGiven's store so jGiven writes the run into it,
 * and the driver renders from the reference it already holds. Inject-the-model — one owner, no
 * static, no null (the model is created host-side, never absent).
 */
@ExtendWith(HostSeeder.class) // ours first: host-facts + the injected model
@ExtendWith(com.tngtech.jgiven.junit5.JGivenExtension.class) // jGiven second
public class ClusterSeedScenario
    extends ScenarioTestBase<
        ClusterSeedScenario.Given, ClusterSeedScenario.When, ClusterSeedScenario.Then>
    implements HostSeeder.HostFactsAware {

  @ProvidedScenarioState HostFacts hostFacts;

  private final Scenario<Given, When, Then> scenario = createScenario();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void acceptHostFacts(HostFacts facts) {
    this.hostFacts = facts;
  }

  @Test
  void the_cluster_is_seeded() {
    when().the_seed_runs();
  }

  public static class Given extends Stage<Given> {}

  public static class When extends Stage<When> {
    @As("the seed runs")
    public When the_seed_runs() {
      return self();
    }
  }

  public static class Then extends Stage<Then> {}
}
