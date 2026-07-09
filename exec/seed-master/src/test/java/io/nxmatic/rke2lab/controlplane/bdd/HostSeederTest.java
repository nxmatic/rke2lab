package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.ScenarioStage;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.junit5.JGivenExtension;
import io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions;
import io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder;
import io.nxmatic.rke2lab.controlplane.resources.ResourceManager;
import io.nxmatic.rke2lab.pipeline.OnFailure;
import io.nxmatic.rke2lab.pulumi.edge.LiveGate;
import io.nxmatic.rke2lab.pulumi.edge.RunMode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The seeder reads {@link HostFacts} from the session store, collects them into a {@link
 * StageContext} carrier, and pushes it into the run's value-DAG BEFORE jGiven initializes the
 * scenario. A stage's {@code @ExpectedScenarioState} then resolves the exact bag the host seeded —
 * the single injection channel, no {@code *Aware} interface. The store is seeded here in a
 * BeforeAllCallback (standing in for the host driver's session-store put).
 */
@ExtendWith(HostSeederTest.FactsStoreSeeder.class) // seeds the store (stands in for the host)
@ExtendWith(HostSeeder.class) // collects into the carrier + pushes into the DAG
@ExtendWith(JGivenExtension.class) // jGiven third
class HostSeederTest
    extends ScenarioTestBase<HostSeederTest.Given, HostSeederTest.When, HostSeederTest.Then> {

  private final Scenario<Given, When, Then> scenario = createScenario();

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Test
  void the_seeder_pushes_host_facts_into_the_dag() {
    when().the_facts_reach_a_stage();
  }

  public static class Given extends Stage<Given> {}

  public static class When extends Stage<When> {
    @ScenarioStage FactsReadingStage facts;

    @As("the seeded host facts reach a stage through the DAG")
    public When the_facts_reach_a_stage() {
      facts.the_seeded_facts_are_the_ones_the_host_put();
      return self();
    }
  }

  public static class Then extends Stage<Then> {}

  /**
   * A stage that reads the DAG-injected {@link HostFacts} and asserts it is the host's exact bag.
   */
  public static class FactsReadingStage extends Stage<FactsReadingStage> {
    @ExpectedScenarioState HostFacts hostFacts;

    @As("the seeded facts are the ones the host put")
    public FactsReadingStage the_seeded_facts_are_the_ones_the_host_put() {
      assertNotNull(hostFacts, "the carrier pushed host-facts into the value-DAG");
      assertSame(FactsStoreSeeder.FACTS, hostFacts, "the exact bag the host seeded");
      return self();
    }
  }

  static final class FactsStoreSeeder implements BeforeAllCallback {
    static final HostFacts FACTS = sampleFacts();

    @Override
    public void beforeAll(ExtensionContext context) {
      context.getStore(HostSeeder.NS).put(HostSeeder.HOST_FACTS, FACTS);
      // The connection is required context; this test's stage never reaches the OSGi world, so an
      // empty stub connection satisfies the seeder's contract.
      context.getStore(HostSeeder.NS).put(HostSeeder.CONNECTION, StubConnection.serving(Map.of()));
    }

    private static HostFacts sampleFacts() {
      final var cfg = OperatorConfiguration.mandatory();
      return new HostFacts(
          cfg.asBootstrapConfig(),
          cfg.asPolicy(),
          BootstrapOptions.from(cfg.asDto()),
          LiveGate.forRun(RunMode.STANDALONE),
          RunMode.STANDALONE.materialises(),
          new BboxReconciliationOrchestrator(false),
          new ResourceManager(),
          new OutputBuilder(),
          message -> {},
          OnFailure.noop(),
          new ConsultationLog());
    }
  }
}
