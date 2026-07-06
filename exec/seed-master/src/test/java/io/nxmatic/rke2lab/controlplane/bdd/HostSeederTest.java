package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import io.nxmatic.rke2lab.controlplane.config.OperatorConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The seeder reads HostFacts from the session store and pushes them onto the scenario instance's
 * {@code @ProvidedScenarioState} BEFORE jGiven's readScenarioState siphons them. The store is
 * seeded here in a BeforeAllCallback (standing in for the host driver's session-store put).
 */
@ExtendWith(HostSeederTest.FactsStoreSeeder.class) // seeds the store (stands in for the host)
@ExtendWith(HostSeeder.class) // reads it onto the instance
class HostSeederTest implements HostSeeder.HostFactsAware {

  @ProvidedScenarioState HostFacts hostFacts;

  @Override
  public void acceptHostFacts(HostFacts facts) {
    this.hostFacts = facts;
  }

  @Test
  void the_seeder_populates_the_instance_host_facts_field() {
    assertNotNull(hostFacts, "seeder should have set the @ProvidedScenarioState field");
    assertSame(FactsStoreSeeder.FACTS, hostFacts, "the exact bag the host seeded");
  }

  static final class FactsStoreSeeder implements org.junit.jupiter.api.extension.BeforeAllCallback {
    static final HostFacts FACTS = sampleFacts();

    @Override
    public void beforeAll(org.junit.jupiter.api.extension.ExtensionContext context) {
      context.getStore(HostSeeder.NS).put(HostSeeder.HOST_FACTS, FACTS);
    }

    private static HostFacts sampleFacts() {
      final var cfg = OperatorConfiguration.mandatory();
      return new HostFacts(
          cfg.asBootstrapConfig(),
          cfg.asPolicy(),
          io.nxmatic.rke2lab.controlplane.pipeline.BootstrapOptions.from(cfg.asDto()),
          io.nxmatic.rke2lab.pulumi.edge.LiveGate.forRun(
              io.nxmatic.rke2lab.pulumi.edge.RunMode.STANDALONE),
          new io.nxmatic.rke2lab.controlplane.bbox.BboxReconciliationOrchestrator(false),
          new io.nxmatic.rke2lab.controlplane.resources.ResourceManager(),
          new io.nxmatic.rke2lab.controlplane.pipeline.OutputBuilder(),
          message -> {},
          io.nxmatic.rke2lab.pipeline.OnFailure.noop(),
          new io.nxmatic.rke2lab.doctor.port.ConsultationLog());
    }
  }
}
